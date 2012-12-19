package com.openxc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import com.google.common.base.Objects;
import com.openxc.controllers.VehicleController;
import com.openxc.measurements.BaseMeasurement;
import com.openxc.measurements.Measurement;
import com.openxc.measurements.UnrecognizedMeasurementTypeException;
import com.openxc.remote.RawMeasurement;
import com.openxc.remote.RemoteServiceController;
import com.openxc.remote.VehicleServiceException;
import com.openxc.remote.VehicleServiceInterface;
import com.openxc.sinks.MeasurementListenerSink;
import com.openxc.sinks.MockedLocationSink;
import com.openxc.sinks.VehicleDataSink;
import com.openxc.sources.RemoteListenerSource;
import com.openxc.sources.SourceCallback;
import com.openxc.sources.VehicleDataSource;
import com.openxc.sources.usb.UsbVehicleDataSource;

/**
 * The VehicleManager is an in-process Android service and the primary entry
 * point into the OpenXC library.
 *
 * An OpenXC application should bind to this service and request vehicle
 * measurements through it either synchronously or asynchronously. The service
 * will shut down when no more clients are bound to it.
 *
 * Synchronous measurements are obtained by passing the type of the desired
 * measurement to the get method. Asynchronous measurements are obtained by
 * defining a Measurement.Listener object and passing it to the service via the
 * addListener method.
 *
 * The sources of data and any post-processing that happens is controlled by
 * modifying a list of "sources" and "sinks". When a message is received from a
 * data source, it is passed to any and all registered message "sinks" - these
 * receivers conform to the {@link com.openxc.sinks.VehicleDataSink}.
 * There will always be at least one sink that stores the latest messages and
 * handles passing on data to users of the VehicleManager class. Other possible
 * sinks include the {@link com.openxc.sinks.FileRecorderSink} which records a
 * trace of the raw OpenXC measurements to a file and a web streaming sink
 * (which streams the raw data to a web application). Other possible sources
 * include the {@link com.openxc.sources.trace.TraceVehicleDataSource} which
 * reads a previously recorded vehicle data trace file and plays back the
 * measurements
 * in real-time.
 *
 * One small inconsistency is that if a Bluetooth data source is added, any
 * calls to set() will be sent to that device. If no Belutooth data source is
 * in the pipeline, the standard USB device will be used instead. There is
 * currently no way to modify this behavior.
 */
public class VehicleManager extends Service implements SourceCallback {
    public final static String VEHICLE_LOCATION_PROVIDER =
            MockedLocationSink.VEHICLE_LOCATION_PROVIDER;
    private final static String TAG = "VehicleManager";
    private boolean mIsBound;
    private Lock mRemoteBoundLock;
    private Condition mRemoteBoundCondition;

    private IBinder mBinder = new VehicleBinder();
    private VehicleServiceInterface mRemoteService;
    private DataPipeline mPipeline;
    private RemoteListenerSource mRemoteSource;
    private CopyOnWriteArrayList<VehicleController> mControllers;
    private VehicleController mRemoteController;
    private MeasurementListenerSink mNotifier;
    // The DataPipeline in this class must only have 1 source - the special
    // RemoteListenerSource that receives measurements from the
    // VehicleService and propagates them to all of the user-registered
    // sinks. Any user-registered sources must live in a separate array,
    // unfortunately, so they don't try to circumvent the VehicleService
    // and send their values directly to the in-process sinks (otherwise no
    // other applications could receive updates from that source). For most
    // applications that might be fine, but since we want to control trace
    // playback from the Enabler, it needs to be able to inject those into the
    // RVS. TODO actually, maybe that's the only case. If there are no other
    // cases where a user application should be able to inject source data for
    // all other apps to share, we should reconsider this and special case the
    // trace source.
    private CopyOnWriteArrayList<VehicleDataSource> mSources;

    /**
     * Binder to connect IBinder in a ServiceConnection with the VehicleManager.
     *
     * This class is used in the onServiceConnected method of a
     * ServiceConnection in a client of this service - the IBinder given to the
     * application can be cast to the VehicleBinder to retrieve the
     * actual service instance. This is required to actaully call any of its
     * methods.
     */
    public class VehicleBinder extends Binder {
        /*
         * Return this Binder's parent VehicleManager instance.
         *
         * @return an instance of VehicleManager.
         */
        public VehicleManager getService() {
            return VehicleManager.this;
        }
    }

    /**
     * Block until the VehicleManager is alive and can return measurements.
     *
     * Most applications don't need this and don't wait this method, but it can
     * be useful for testing when you need to make sure you will get a
     * measurement back from the system.
     */
    public void waitUntilBound() {
        mRemoteBoundLock.lock();
        Log.i(TAG, "Waiting for the VehicleService to bind to " + this);
        while(!mIsBound) {
            try {
                mRemoteBoundCondition.await();
            } catch(InterruptedException e) {}
        }
        Log.i(TAG, mRemoteService + " is now bound");
        mRemoteBoundLock.unlock();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service starting");

        mRemoteBoundLock = new ReentrantLock();
        mRemoteBoundCondition = mRemoteBoundLock.newCondition();

        mPipeline = new DataPipeline();
        initializeDefaultSinks(mPipeline);
        mSources = new CopyOnWriteArrayList<VehicleDataSource>();
        mControllers = new CopyOnWriteArrayList<VehicleController>();
        bindRemote();
    }

    private void initializeDefaultSinks(DataPipeline pipeline) {
        mNotifier = new MeasurementListenerSink();
        pipeline.addSink(mNotifier);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Service being destroyed");
        if(mPipeline != null) {
            mPipeline.stop();
        }
        unbindRemote();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "Service binding in response to " + intent);
        bindRemote();
        return mBinder;
    }

    /**
     * Retrieve the most current value of a measurement.
     *
     * Regardless of if a measurement is available or not, return a
     * Measurement instance of the specified type. The measurement can be
     * checked to see if it has a value.
     *
     * @param measurementType The class of the requested Measurement
     *      (e.g. VehicleSpeed.class)
     * @return An instance of the requested Measurement which may or may
     *      not have a value.
     * @throws UnrecognizedMeasurementTypeException if passed a measurementType
     *      that does not extend Measurement
     * @throws NoValueException if no value has yet been received for this
     *      measurementType
     * @see BaseMeasurement
     */
    public Measurement get(
            Class<? extends Measurement> measurementType)
            throws UnrecognizedMeasurementTypeException, NoValueException {

        if(mRemoteService == null) {
            Log.w(TAG, "Not connected to the VehicleService -- " +
                    "throwing a NoValueException");
            throw new NoValueException();
        }

        Log.d(TAG, "Looking up measurement for " + measurementType);
        try {
            RawMeasurement rawMeasurement = mRemoteService.get(
                    BaseMeasurement.getIdForClass(measurementType));
            return BaseMeasurement.getMeasurementFromRaw(measurementType,
                    rawMeasurement);
        } catch(RemoteException e) {
            Log.w(TAG, "Unable to get value from remote vehicle service", e);
            throw new NoValueException();
        }
    }

    /**
     * Send a command back to the vehicle.
     *
     * This fails silently if it is unable to connect to the remote vehicle
     * service.
     *
     * @param command The desired command to send to the vehicle.
     */
    public void set(Measurement command) throws
                UnrecognizedMeasurementTypeException {
        Log.d(TAG, "Sending command " + command);
        RawMeasurement rawCommand = command.toRaw();

        for(VehicleController controller : mControllers) {
            Log.d(TAG, "Sending " + rawCommand + " using controller " +
                    controller);
            // TODO change API to have set reutrn a boolean so we keep trying
            // until one responds strue
            controller.set(rawCommand);
            return;
        }
    }

    /**
     * Register to receive async updates for a specific Measurement type.
     *
     * Use this method to register an object implementing the
     * Measurement.Listener interface to receive real-time updates
     * whenever a new value is received for the specified measurementType.
     *
     * @param measurementType The class of the Measurement
     *      (e.g. VehicleSpeed.class) the listener was listening for
     * @param listener An Measurement.Listener instance that was
     *      previously registered with addListener
     * @throws VehicleServiceException if the listener is unable to be
     *      unregistered with the library internals - an exceptional situation
     *      that shouldn't occur.
     * @throws UnrecognizedMeasurementTypeException if passed a measurementType
     *      not extend Measurement
     */
    public void addListener(
            Class<? extends Measurement> measurementType,
            Measurement.Listener listener)
            throws VehicleServiceException,
            UnrecognizedMeasurementTypeException {
        Log.i(TAG, "Adding listener " + listener + " to " + measurementType);
        mNotifier.register(measurementType, listener);
    }

    /**
     * Reset vehicle service to use only the default vehicle sources.
     *
     * The default vehicle data source is USB. If a USB CAN translator is not
     * connected, there will be no more data.
     *
     * @throws VehicleServiceException if the listener is unable to be
     *      unregistered with the library internals - an exceptional situation
     *      that shouldn't occur.
     */
    public void initializeDefaultSources()
            throws VehicleServiceException {
        Log.i(TAG, "Resetting data sources");
        if(mRemoteService != null) {
            try {
                mRemoteService.initializeDefaultSources();
            } catch(RemoteException e) {
                throw new VehicleServiceException(
                        "Unable to reset data sources");
            }
        } else {
            Log.w(TAG, "Can't reset data sources -- " +
                    "not connected to remote service yet");
        }
    }

    public void clearSources() throws VehicleServiceException {
        Log.i(TAG, "Clearing all data sources");
        if(mRemoteService != null) {
            try {
                mRemoteService.clearSources();
            } catch(RemoteException e) {
                throw new VehicleServiceException(
                        "Unable to clear data sources");
            }
        } else {
            Log.w(TAG, "Can't clear all data sources -- " +
                    "not connected to remote service yet");
        }
        mSources.clear();
    }

    /**
     * Unregister a previously reigstered Measurement.Listener instance.
     *
     * When an application is no longer interested in received measurement
     * updates (e.g. when it's pausing or exiting) it should unregister all
     * previously registered listeners to save on CPU.
     *
     * @param measurementType The class of the requested Measurement
     *      (e.g. VehicleSpeed.class)
     * @param listener An object implementing the Measurement.Listener
     *      interface that should be called with any new measurements.
     * @throws VehicleServiceException if the listener is unable to be
     *      registered with the library internals - an exceptional situation
     *      that shouldn't occur.
     * @throws UnrecognizedMeasurementTypeException if passed a class that does
     *      not extend Measurement
     */
    public void removeListener(Class<? extends Measurement>
            measurementType, Measurement.Listener listener)
            throws VehicleServiceException {
        Log.i(TAG, "Removing listener " + listener + " from " +
                measurementType);
        mNotifier.unregister(measurementType, listener);
    }

    /**
     * Add a new data source to the vehicle service.
     *
     * For example, to use the trace data source to playback a trace file, call
     * the addSource method after binding with VehicleManager:
     *
     *      service.addSource(new TraceVehicleDataSource(
     *                  new URI("/sdcard/openxc/trace.json"))));
     *
     * The {@link UsbVehicleDataSource} exists by default with the default USB
     * device ID. To clear all existing sources, use the {@link #clearSources()}
     * method. To revert back to the default set of sources, use
     * {@link #initializeDefaultSources}.
     *
     * @param source an instance of a VehicleDataSource
     */
    public void addSource(VehicleDataSource source) {
        Log.i(TAG, "Adding data source " + source);
        source.setCallback(this);
        mSources.add(source);
    }

    /**
     * Add a new vehicle controller to the service.
     *
     * For example, to use a Bluetooth CAN translator as a vehicle controller in
     * additional to a vehicle data source, call the addController method after
     * binding with VehicleManager:
     *
     *      service.addController(mBluetoothSource);
     *
     * The {@link UsbVehicleDataSource} is initialized as a controller by
     * default, the same as it is a data source. The USB data source will be
     * used as a controller only if no other VehicleControllers are availab.e
     *
     * @param controller an instance of a VehicleController
     */
    public void addController(VehicleController controller) {
        Log.i(TAG, "Adding controller: " + controller);
        mControllers.add(controller);
    }

    /**
     * Return a list of all sources active in the system, suitable for
     * displaying in a status view.
     *
     * This method is soley for being able to peek into the system to see what's
     * active, which is why it returns strings intead of the actual source
     * objects. We don't want applications to be able to modify the sources
     * through this method.
     *
     * @return A list of the names and status of all sources.
     */
    public List<String> getSourceSummaries() {
        ArrayList<String> sources = new ArrayList<String>();
        for(VehicleDataSource source : mSources) {
            sources.add(source.toString());
        }

        for(VehicleDataSource source : mPipeline.getSources()) {
            sources.add(source.toString());
        }

        if(mRemoteService != null) {
            try {
                sources.addAll(mRemoteService.getSourceSummaries());
            } catch(RemoteException e) {
                Log.w(TAG, "Unable to retreive remote source summaries", e);
            }

        }
        return sources;
    }

    /**
     * Return a list of all sinks active in the system.
     *
     * The motivation for this method is the same as {@link #getSources}.
     *
     * @return A list of the names and status of all sinks.
     */
    public List<String> getSinkSummaries() {
        ArrayList<String> sinks = new ArrayList<String>();
        for(VehicleDataSink sink : mPipeline.getSinks()) {
            sinks.add(sink.toString());
        }

        if(mRemoteService != null) {
            try {
                sinks.addAll(mRemoteService.getSinkSummaries());
            } catch(RemoteException e) {
                Log.w(TAG, "Unable to retreive remote sink summaries", e);
            }
        }
        return sinks;
    }

    /**
     * Remove a previously registered source from the data pipeline.
     */
    public void removeSource(VehicleDataSource source) {
        if(source != null) {
            mSources.remove(source);
            source.stop();
        }
    }

    /**
     * Remove a previously registered controller from the service.
     */
    public void removeController(VehicleController controller) {
        if(controller != null) {
            mControllers.remove(controller);
        }
    }

    /**
     * Add a new data sink to the vehicle service.
     *
     * A data sink added with this method will receive all new measurements as
     * they arrive from registered data sources.  For example, to use the trace
     * file recorder sink, call the addSink method after binding with
     * VehicleManager:
     *
     *      service.addSink(new FileRecorderSink(
     *              new AndroidFileOpener("openxc", this)));
     *
     * @param sink an instance of a VehicleDataSink
     */
    public void addSink(VehicleDataSink sink) {
        Log.i(TAG, "Adding data sink " + sink);
        mPipeline.addSink(sink);
    }

    /**
     * Remove a previously registered sink from the data pipeline.
     */
    public void removeSink(VehicleDataSink sink) {
        if(sink != null) {
            mPipeline.removeSink(sink);
            sink.stop();
        }
    }

    /**
     * Read the number of messages received by the vehicle service.
     *
     * @throws VehicleServiceException if the listener is unable to be
     *      unregistered with the library internals - an exceptional situation
     *      that shouldn't occur.
     */
    public int getMessageCount() throws VehicleServiceException {
        if(mRemoteService != null) {
            try {
                return mRemoteService.getMessageCount();
            } catch(RemoteException e) {
                throw new VehicleServiceException(
                        "Unable to retrieve message count", e);
            }
        } else {
            throw new VehicleServiceException(
                    "Unable to retrieve message count");
        }
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
            .add("bound", mIsBound)
            .toString();
    }

    public void receive(RawMeasurement measurement) {
        if(mRemoteService != null) {
            try {
                mRemoteService.receive(measurement);
            } catch(RemoteException e) {
                Log.d(TAG, "Unable to send message to remote service", e);
            }
        }
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className,
                IBinder service) {
            Log.i(TAG, "Bound to VehicleService");
            mRemoteService = VehicleServiceInterface.Stub.asInterface(
                    service);
            mRemoteController = new RemoteServiceController(mRemoteService);
            mControllers.add(mRemoteController);

            mRemoteSource = new RemoteListenerSource(mRemoteService);
            mPipeline.addSource(mRemoteSource);

            mRemoteBoundLock.lock();
            mIsBound = true;
            mRemoteBoundCondition.signal();
            mRemoteBoundLock.unlock();
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.w(TAG, "VehicleService disconnected unexpectedly");
            mControllers.remove(mRemoteController);
            mRemoteService = null;
            mIsBound = false;
            mPipeline.removeSource(mRemoteSource);
        }
    };

    private void bindRemote() {
        Log.i(TAG, "Binding to VehicleService");
        Intent intent = new Intent(
                VehicleServiceInterface.class.getName());
        try {
            bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        } catch(SecurityException e) {
            Log.e(TAG, "Unable to bind with remote service, it's not exported "
                    + "-- is the instrumentation tests package installed?", e);
            Toast.makeText(this, "Vehicle service is not exported and is " +
                    "inaccessible - are the instrumentation tests still " +
                    "installed?", Toast.LENGTH_LONG).show();
        }
    }

    private void unbindRemote() {
        if(mRemoteBoundLock != null) {
            mRemoteBoundLock.lock();
        }

        if(mIsBound) {
            Log.i(TAG, "Unbinding from VehicleService");
            unbindService(mConnection);
            mIsBound = false;
        }

        if(mRemoteBoundLock != null) {
            mRemoteBoundLock.unlock();
        }
    }
}
