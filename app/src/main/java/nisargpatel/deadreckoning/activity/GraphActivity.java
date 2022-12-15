package nisargpatel.deadreckoning.activity;

import static android.telephony.CellInfo.CONNECTION_PRIMARY_SERVING;

import java.lang.Math.*;
import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
//import android.location.Location;
//import android.location.LocationListener;
//import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.telephony.CellInfo;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.telephony.CellIdentity;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import nisargpatel.deadreckoning.R;
import nisargpatel.deadreckoning.extra.ExtraFunctions;
import nisargpatel.deadreckoning.filewriting.DataFileWriter;
import nisargpatel.deadreckoning.graph.ScatterPlot;
import nisargpatel.deadreckoning.orientation.GyroscopeDeltaOrientation;
import nisargpatel.deadreckoning.orientation.GyroscopeEulerOrientation;
import nisargpatel.deadreckoning.orientation.MagneticFieldOrientation;
import nisargpatel.deadreckoning.stepcounting.DynamicStepCounter;

//public class GraphActivity extends AppCompatActivity implements SensorEventListener, LocationListener {
public class GraphActivity extends AppCompatActivity implements SensorEventListener {

    // private static final long GPS_SECONDS_PER_WEEK = 511200L;

    private static final float GYROSCOPE_INTEGRATION_SENSITIVITY = 0.0025f;

    private static final String FOLDER_NAME = "Dead_Reckoning/Graph_Activity";
    private static final String[] DATA_FILE_NAMES = {
            "Initial_Orientation",
            "Linear_Acceleration",
            "Gyroscope_Uncalibrated",
            "Magnetic_Field_Uncalibrated",
            "Gravity",
            "XY_Data_Set"
    };
    private static final String[] DATA_FILE_HEADINGS = {
            "Initial_Orientation",
            "Linear_Acceleration" + "\n" + "t;Ax;Ay;Az;findStep",
            "Gyroscope_Uncalibrated" + "\n" + "t;uGx;uGy;uGz;xBias;yBias;zBias;heading",
            "Magnetic_Field_Uncalibrated" + "\n" + "t;uMx;uMy;uMz;xBias;yBias;zBias;heading",
            "Gravity" + "\n" + "t;gx;gy;gz",
            //"XY_Data_Set" + "\n" + "weekGPS;secGPS;t;strideLength;magHeading;gyroHeading;originalPointX;originalPointY;rotatedPointX;rotatedPointY"
            "XY_Data_Set" + "\n" + "t;strideLength;magHeading;gyroHeading;originalPointX;originalPointY;rotatedPointX;rotatedPointY"
    };

    private DynamicStepCounter dynamicStepCounter;
    private GyroscopeDeltaOrientation gyroscopeDeltaOrientation;
    private GyroscopeEulerOrientation gyroscopeEulerOrientation;
    private DataFileWriter dataFileWriter;
    private ScatterPlot scatterPlot;

    private FloatingActionButton fabButton;
    private LinearLayout mLinearLayout;

    private SensorManager sensorManager;
    // private LocationManager locationManager;

    float[] gyroBias;
    float[] magBias;
    float[] currGravity; //current gravity
    float[] currMag; //current magnetic field
    private boolean isRunning;
    private boolean isCalibrated;
    private boolean usingDefaultCounter;
    private boolean areFilesCreated;
    private float strideLength;
    private float gyroHeading;
    private float magHeading;
    // private float weeksGPS;
    // private float secondsGPS;

    private long startTime;
    private boolean firstRun;
    private float newX = 0, newY = 0, prevX = 0, prevY = 0;

    private float initialHeading;

    //for getting cellinfo
    private TelephonyManager telephonyManager;
    private int prevPci = -1;

    private int getPci(CellIdentity cellIdentity) {
        if (cellIdentity instanceof CellIdentityLte) {
            return ((CellIdentityLte) cellIdentity).getPci();
        } else if (cellIdentity instanceof CellIdentityNr) {
            return ((CellIdentityNr) cellIdentity).getPci();
        }
        return -2;
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_graph);

        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        // get location permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(GraphActivity.this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, 0);
            finish();
        }
/*
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(GraphActivity.this, new String[] {
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            },0);
            finish();
        }
*/

        //defining needed variables
        gyroBias = null;
        magBias = null;
        currGravity = null;
        currMag = null;

        String counterSensitivity;

        isRunning = isCalibrated = usingDefaultCounter = areFilesCreated = false;
        firstRun = true;
        strideLength = 0;
        initialHeading = gyroHeading = magHeading = 0;
        // weeksGPS = secondsGPS = 0;
        startTime = 0;

        //getting global settings
        strideLength = getIntent().getFloatExtra("stride_length", 2.5f);
        isCalibrated = getIntent().getBooleanExtra("is_calibrated", false);
        gyroBias = getIntent().getFloatArrayExtra("gyro_bias");
        magBias = getIntent().getFloatArrayExtra("mag_bias");

        //using user_name to get index of user in userList, which is also the index of the user's stride_length
        counterSensitivity = getIntent().getStringExtra("preferred_step_counter");

        //usingDefaultCounter is counterSensitivity = "default" and sensor is available
        /*
        usingDefaultCounter = counterSensitivity.equals("default") &&
                getIntent().getBooleanExtra("step_detector", false);

         */

        //initializing needed classes
        gyroscopeDeltaOrientation = new GyroscopeDeltaOrientation(GYROSCOPE_INTEGRATION_SENSITIVITY, gyroBias);
        // if (usingDefaultCounter) //if using default TYPE_STEP_DETECTOR, don't need DynamicStepCounter
        //    dynamicStepCounter = null;
        if (!counterSensitivity.equals("default") && Double.parseDouble(counterSensitivity) > 0) {
            dynamicStepCounter = new DynamicStepCounter(Double.parseDouble(counterSensitivity));
            Log.d("dynamicStepCounter",  counterSensitivity);
        } else {//if cannot use TYPE_STEP_DETECTOR but sensitivity = "default", use 1.0 sensitivity until user calibrates
            dynamicStepCounter = new DynamicStepCounter(1.4);
            Log.d("dynamicStepCounter",  "default value");
        }

        //defining views
        fabButton = findViewById(R.id.fab);
        mLinearLayout = findViewById(R.id.linearLayoutGraph);

        //setting up graph with origin
        scatterPlot = new ScatterPlot("Position");
        scatterPlot.addPoint(0, 0);
        mLinearLayout.addView(scatterPlot.getGraphView(getApplicationContext()));

        //message user w/ user_name and stride_length info
        Toast.makeText(GraphActivity.this, "Stride Length: " + strideLength, Toast.LENGTH_SHORT).show();

        //starting GPS location tracking
        // locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        // locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, GraphActivity.this);

        //starting sensors
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensorManager.registerListener(GraphActivity.this,
                sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY),
                10000);

        if (isCalibrated) {
            sensorManager.registerListener(GraphActivity.this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED),
                    10000);
            sensorManager.registerListener(GraphActivity.this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED),
                    10000);
        } else {
            sensorManager.registerListener(GraphActivity.this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
                    10000);
            sensorManager.registerListener(GraphActivity.this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                    10000);
        }

        if (usingDefaultCounter) {
            sensorManager.registerListener(GraphActivity.this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR),
                    10000);
        } else {
            sensorManager.registerListener(GraphActivity.this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION),
                    10000);
        }

        //setting up buttons
        fabButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if (!isRunning) {

                    isRunning = true;

                    createFiles();

                    //if (usingDefaultCounter)
                    //dataFileWriter.writeToFile("Linear_Acceleration",
                    //        "TYPE_LINEAR_ACCELERATION will not be recorded, since the TYPE_STEP_DETECTOR is being used instead."
                    //);

                    float[][] initialOrientation = MagneticFieldOrientation.getOrientationMatrix(currGravity, currMag, magBias);
                    initialHeading = MagneticFieldOrientation.getHeading(currGravity, currMag, magBias);

                    //saving initial orientation data
                    //dataFileWriter.writeToFile("Initial_Orientation", "init_Gravity: " + Arrays.toString(currGravity));
                    //dataFileWriter.writeToFile("Initial_Orientation", "init_Mag: " + Arrays.toString(currMag));
                    //dataFileWriter.writeToFile("Initial_Orientation", "mag_Bias: " + Arrays.toString(magBias));
                    //dataFileWriter.writeToFile("Initial_Orientation", "gyro_Bias: " + Arrays.toString(gyroBias));
                    //dataFileWriter.writeToFile("Initial_Orientation", "init_Orientation: " + Arrays.deepToString(initialOrientation));
                    //dataFileWriter.writeToFile("Initial_Orientation", "init_Heading: " + initialHeading);

//                Log.d("init_heading", "" + initialHeading);

                    //TODO: fix rotation matrix
                    //gyroscopeEulerOrientation = new GyroscopeEulerOrientation(initialOrientation);

                    gyroscopeEulerOrientation = new GyroscopeEulerOrientation(ExtraFunctions.IDENTITY_MATRIX);

                    /*
                    dataFileWriter.writeToFile("XY_Data_Set", "Initial_orientation: " +
                            Arrays.deepToString(initialOrientation));
                    dataFileWriter.writeToFile("Gyroscope_Uncalibrated", "Gyroscope_bias: " +
                            Arrays.toString(gyroBias));
                    dataFileWriter.writeToFile("Magnetic_Field_Uncalibrated", "Magnetic_field_bias:" +
                            Arrays.toString(magBias));

                     */

                    fabButton.setImageDrawable(ContextCompat.getDrawable(GraphActivity.this, R.drawable.ic_pause_black_24dp));

                } else {

                    firstRun = true;
                    isRunning = false;

                    fabButton.setImageDrawable(ContextCompat.getDrawable(GraphActivity.this, R.drawable.ic_play_arrow_black_24dp));

                }


            }


        });

        mLinearLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                //complimentary filter
                float compHeading = ExtraFunctions.calcCompHeading(magHeading, gyroHeading);

//                Log.d("comp_heading", "" + compHeading);

                //getting and rotating the previous XY points so North 0 on unit circle
                float oPointX = scatterPlot.getLastYPoint();
                float oPointY = -scatterPlot.getLastXPoint();

                //calculating XY points from heading and stride_length
                oPointX += ExtraFunctions.getXFromPolar(strideLength, compHeading);
                oPointY += ExtraFunctions.getYFromPolar(strideLength, compHeading);

                //rotating points by 90 degrees, so north is up
                float rPointX = -oPointY;
                float rPointY = oPointX;
                newX = rPointX;
                newY = rPointY;
                scatterPlot.addPoint(rPointX, rPointY);

                mLinearLayout.removeAllViews();
                mLinearLayout.addView(scatterPlot.getGraphView(getApplicationContext()));

            }
        });



        // start cellinfo tracking
        Looper looper = getMainLooper();
        Handler handler = new Handler(looper);
        Runnable runnable = new Runnable() {

            @SuppressLint("MissingPermission")
            @Override
            public void run() {

                handler.postDelayed(this, 10000);

                TelephonyManager.CellInfoCallback callback = new TelephonyManager.CellInfoCallback() {
                    @Override
                    public void onCellInfo(@NonNull List<CellInfo> cellInfo) {
                        Log.d("cellInfo size", String.valueOf(cellInfo.size()));
                        for (int i = 0; i < cellInfo.size(); i++) {
                            CellInfo it = cellInfo.get(i);
                            Log.d("Current serving cell", it.toString());
                            if (it.getCellConnectionStatus() == CONNECTION_PRIMARY_SERVING) {
                                Log.d("Current serving cell", it.toString());
//                                String s = "No Cell Found\n";
//                                s = it.getCellIdentity().toString() + "\n" +
//                                        it.getCellSignalStrength().getDbm() + "\n" +
//                                        it.getTimestampMillis();
                                if (prevPci == -1) {
                                    prevPci = getPci(it.getCellIdentity());
                                }
                                else {
                                    int currPci = getPci(it.getCellIdentity());
                                    if (currPci == -2) {
                                        Toast.makeText(getApplicationContext(),
                                                "Downgrade Detected!", Toast.LENGTH_SHORT).show();
                                        prevPci = currPci;
                                    }
                                    else if (currPci != prevPci) {
                                        Toast.makeText(getApplicationContext(),
                                                "Cell Change Detected!", Toast.LENGTH_SHORT).show();
                                        Log.d("x", Float.toString(newX));
                                        Log.d("y", Float.toString(newY));
                                        Log.d("prevx", Float.toString(prevX));
                                        Log.d("prevy", Float.toString(prevY));



                                        float dist = (float)Math.sqrt(Math.pow((double)(prevX - newX), 2) + Math.pow((double)(prevY - newY), 2));
                                        Toast.makeText(getApplicationContext(),
                                                "Cell Change Detected!\n" +
                                                        "distance since last change: " + Float.toString(dist) + "feet", Toast.LENGTH_SHORT).show();
                                        prevX = newX;
                                        prevY = newY;
                                        prevPci = currPci;
                                    }
                                }
                            }
                        }
                    }
                };
                telephonyManager.requestCellInfoUpdate(getMainExecutor(), callback);
            }
        };
        handler.post(runnable);



    }

    @Override
    protected void onStop() {
        super.onStop();
        sensorManager.unregisterListener(this);
        // locationManager.removeUpdates(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (isRunning) {

            /*
            // get location permission
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    || ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(GraphActivity.this, new String[] {
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                },0);
                finish();
            }

            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, GraphActivity.this);

             */

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(GraphActivity.this, new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, 0);
                finish();
            }

            if (isCalibrated) {
                sensorManager.registerListener(GraphActivity.this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED),
                        10000);
                sensorManager.registerListener(GraphActivity.this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED),
                        10000);
            } else {
                sensorManager.registerListener(GraphActivity.this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
                        10000);
                sensorManager.registerListener(GraphActivity.this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                        10000);
            }

            if (usingDefaultCounter) {
                sensorManager.registerListener(GraphActivity.this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR),
                        10000);
            } else {
                sensorManager.registerListener(GraphActivity.this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION),
                        10000);
            }

            fabButton.setImageDrawable(ContextCompat.getDrawable(GraphActivity.this, R.drawable.ic_pause_black_24dp));

        } else {

            fabButton.setImageDrawable(ContextCompat.getDrawable(GraphActivity.this, R.drawable.ic_play_arrow_black_24dp));

        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onSensorChanged(SensorEvent event) {

        if (firstRun) {
            startTime = event.timestamp;
            firstRun = false;
        }

        if (event.sensor.getType() == Sensor.TYPE_GRAVITY) {
            currGravity = event.values;
//            Log.d("gravity_values", Arrays.toString(event.values));
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD ||
                event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) {
            currMag = event.values;
//            Log.d("mag_values", Arrays.toString(event.values));
        }


        if (isRunning) {
            if (event.sensor.getType() == Sensor.TYPE_GRAVITY) {
                ArrayList<Float> dataValues = ExtraFunctions.arrayToList(event.values);
                dataValues.add(0, (float) (event.timestamp - startTime));
                //dataFileWriter.writeToFile("Gravity", dataValues);
            } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD || event.sensor.getType() ==
                    Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) {

                magHeading = MagneticFieldOrientation.getHeading(currGravity, currMag, magBias);

//                Log.d("mag_heading", "" + magHeading);

                //saving magnetic field data
                ArrayList<Float> dataValues = ExtraFunctions.createList(
                        event.values[0], event.values[1], event.values[2],
                        magBias[0], magBias[1], magBias[2]
                );
                dataValues.add(0, (float) (event.timestamp - startTime));
                dataValues.add(magHeading);
                // dataFileWriter.writeToFile("Magnetic_Field_Uncalibrated", dataValues);

            } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE ||
                    event.sensor.getType() == Sensor.TYPE_GYROSCOPE_UNCALIBRATED) {

                float[] deltaOrientation = gyroscopeDeltaOrientation.calcDeltaOrientation(event.timestamp, event.values);

                gyroHeading = gyroscopeEulerOrientation.getHeading(deltaOrientation);
                gyroHeading += initialHeading;

//                Log.d("gyro_heading", "" + gyroHeading);

                //saving gyroscope data
                ArrayList<Float> dataValues = ExtraFunctions.createList(
                        event.values[0], event.values[1], event.values[2],
                        gyroBias[0], gyroBias[1], gyroBias[2]
                );
                dataValues.add(0, (float) (event.timestamp - startTime));
                dataValues.add(gyroHeading);
                // dataFileWriter.writeToFile("Gyroscope_Uncalibrated", dataValues);

            } else if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {

                float norm = ExtraFunctions.calcNorm(
                        event.values[0] +
                                event.values[1] +
                                event.values[2]
                );

                //if step is found, findStep == true
                boolean stepFound = dynamicStepCounter.findStep(norm);

                if (stepFound) {

                    //saving linear acceleration data
                    ArrayList<Float> dataValues = ExtraFunctions.arrayToList(event.values);
                    dataValues.add(0, (float) (event.timestamp - startTime));
                    dataValues.add(1f);
                    // dataFileWriter.writeToFile("Linear_Acceleration", dataValues);

                    //complimentary filter
                    float compHeading = ExtraFunctions.calcCompHeading(magHeading, gyroHeading);

                    //Log.d("comp_heading", "" + compHeading);

                    //getting and rotating the previous XY points so North 0 on unit circle
                    float oPointX = scatterPlot.getLastYPoint();
                    float oPointY = -scatterPlot.getLastXPoint();

                    //calculating XY points from heading and stride_length
                    oPointX += ExtraFunctions.getXFromPolar(strideLength, gyroHeading);
                    oPointY += ExtraFunctions.getYFromPolar(strideLength, gyroHeading);

                    //rotating points by 90 degrees, so north is up
                    float rPointX = -oPointY;
                    float rPointY = oPointX;

                    newX = rPointX;
                    newY = rPointY;

                    scatterPlot.addPoint(rPointX, rPointY);

                    /*
                    //saving XY location data
                    dataFileWriter.writeToFile("XY_Data_Set",
                            // weeksGPS,
                            // secondsGPS,
                            (event.timestamp - startTime),
                            strideLength,
                            magHeading,
                            gyroHeading,
                            oPointX,
                            oPointY,
                            rPointX,
                            rPointY);

                     */

                    mLinearLayout.removeAllViews();
                    mLinearLayout.addView(scatterPlot.getGraphView(getApplicationContext()));

                    //if step is not found
                } else {
                    //saving linear acceleration data
                    ArrayList<Float> dataValues = ExtraFunctions.arrayToList(event.values);
                    dataValues.add(0, (float) event.timestamp);
                    dataValues.add(0f);
                    // dataFileWriter.writeToFile("Linear_Acceleration", dataValues);
                }

            } else if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {

                boolean stepFound = (event.values[0] == 1);

                if (stepFound) {

                    //complimentary filter
                    float compHeading = ExtraFunctions.calcCompHeading(magHeading, gyroHeading);

                    //Log.d("comp_heading", "" + compHeading);

                    //getting and rotating the previous XY points so North 0 on unit circle
                    float oPointX = scatterPlot.getLastYPoint();
                    float oPointY = -scatterPlot.getLastXPoint();

                    //calculating XY points from heading and stride_length
                    oPointX += ExtraFunctions.getXFromPolar(strideLength, gyroHeading);
                    oPointY += ExtraFunctions.getYFromPolar(strideLength, gyroHeading);

                    //rotating points by 90 degrees, so north is up
                    float rPointX = -oPointY;
                    float rPointY = oPointX;
                    newX = rPointX;
                    newY = rPointY;

                    scatterPlot.addPoint(rPointX, rPointY);

                    /*
                    //saving XY location data
                    dataFileWriter.writeToFile("XY_Data_Set",
                            // weeksGPS,
                            // secondsGPS,
                            (event.timestamp - startTime),
                            strideLength,
                            magHeading,
                            gyroHeading,
                            oPointX,
                            oPointY,
                            rPointX,
                            rPointY);

                     */

                    mLinearLayout.removeAllViews();
                    mLinearLayout.addView(scatterPlot.getGraphView(getApplicationContext()));
                }

            }

            /*
            TelephonyManager.CellInfoCallback callback = new TelephonyManager.CellInfoCallback() {
                @Override
                public void onCellInfo(@NonNull List<CellInfo> cellInfo) {
                    Log.d("cellInfo size", String.valueOf(cellInfo.size()));
                    for (int i = 0; i < cellInfo.size(); i++) {
                        CellInfo it = cellInfo.get(i);
                        Log.d("Current serving cell", it.toString());
                        if (it.getCellConnectionStatus() == CONNECTION_PRIMARY_SERVING) {
                            Log.d("Current serving cell", it.toString());
//                                String s = "No Cell Found\n";
//                                s = it.getCellIdentity().toString() + "\n" +
//                                        it.getCellSignalStrength().getDbm() + "\n" +
//                                        it.getTimestampMillis();
                            if (prevPci == -1) {
                                prevPci = getPci(it.getCellIdentity());
                            } else {
                                int currPci = getPci(it.getCellIdentity());
                                if (currPci == -2) {
                                    Toast.makeText(getApplicationContext(),
                                            "Downgrade Detected!", Toast.LENGTH_SHORT).show();
                                    prevPci = currPci;
                                } else if (currPci != prevPci) {
                                    Log.d("x", Float.toString(newX));
                                    Log.d("y", Float.toString(newY));
                                    Log.d("prevx", Float.toString(prevX));
                                    Log.d("prevy", Float.toString(prevY));



                                    float dist = (float)Math.sqrt(Math.pow((double)(prevX - newX), 2) + Math.pow((double)(prevY - newY), 2));
                                    Toast.makeText(getApplicationContext(),
                                            "Cell Change Detected!\n" +
                                                    "distance since last change: " + Float.toString(dist) + "feet", Toast.LENGTH_SHORT).show();


                                    // Toast.makeText(getApplicationContext(),"Cell Change Detected!\n" , Toast.LENGTH_SHORT).show();
                                    prevX = newX;
                                    prevY = newY;
                                    prevPci = currPci;
                                }
                            }
                        }
                    }
                }
            };
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.d("no ACCESS_FINE_LOCATION", "ACCESS_FINE_LOCATION");
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            telephonyManager.requestCellInfoUpdate(getMainExecutor(), callback);


             */

        }

    }

    /*
    @Override
    public void onLocationChanged(Location location) {
        long GPSTimeSec = location.getTime() / 1000;
        weeksGPS = GPSTimeSec / GPS_SECONDS_PER_WEEK;
        secondsGPS = GPSTimeSec % GPS_SECONDS_PER_WEEK;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}

     */

    private void createFiles() {
        /*
        if (!areFilesCreated) {
            try {
                dataFileWriter = new DataFileWriter(FOLDER_NAME, DATA_FILE_NAMES, DATA_FILE_HEADINGS);
            } catch (IOException e) {
                Log.e("GraphActivity", e.toString());
            }
            areFilesCreated = true;
        }

         */
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode){
            case 0:
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(GraphActivity.this, "Thank you for providing permission!", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    // Toast.makeText(GraphActivity.this, "Need location permission to create tour.", Toast.LENGTH_LONG).show();
                    Toast.makeText(GraphActivity.this, "Need file permission to create tour.", Toast.LENGTH_LONG).show();
                    finish();
                }
                break;
        }
    }


}

