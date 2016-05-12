package com.example.junyu.tapdetection;

import android.content.Context;
import android.content.res.AssetManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.*;
import android.os.Process;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.apache.commons.lang3.ArrayUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import libsvm.svm;
import libsvm.svm_model;

public class MainActivity extends AppCompatActivity {
    private final String LOG_TAG = "Main activity";

    private SensorManager sensorManager;
    private SensorEventListener sensorListener;

    private LinkedList<double[]> linAccSamples = new LinkedList<>(Arrays.asList(new double[15][3]));
    private LinkedList<double[]> gyroSamples = new LinkedList<>(Arrays.asList(new double[15][3]));

    private SVMPredict svmPredict;

    private SVMScale tapOccurrenceScaler;
    private SVMScale holdingHandScaler;
    private SVMScale lHandLocScaler;
    private SVMScale rHandLocScaler;

    private svm_model tap_occurrence_model;
    private svm_model holding_hand_model;
    private svm_model lhand_location_model;
    private svm_model rhand_location_model;

    // The list containing lin acc and gyro samples
    private static ArrayList<LinkedList<double[]>> sensorValuesList = new ArrayList<>(2);
    private static BlockingQueue<ArrayList<LinkedList<double[]>>> sensorValuesQueue = new LinkedBlockingQueue<>(50);

    private boolean detectTaps = false;

    private Object lock = new Object();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar appBar = (Toolbar) findViewById(R.id.app_bar);
        setSupportActionBar(appBar);
    }

    @Override
    protected void onStart() {
        super.onStart();
        loadModels();
        loadScalers();

        svmPredict = new SVMPredict();

        // dummy linkedlists so that the set function can be called
        sensorValuesList.add(new LinkedList<> (Arrays.asList(new double[] {0,0,0})));
        sensorValuesList.add(new LinkedList<> (Arrays.asList(new double[] {0,0,0})));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (detectTaps) {
            startListening();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorListener != null) {
            sensorManager.unregisterListener(sensorListener);
        }
        setDetectTaps(false);
    }

    private void loadModels() {
        AssetManager assetManager = getAssets();
        try {
            tap_occurrence_model = svm.svm_load_model(
                    new BufferedReader(
                            new InputStreamReader(
                                    assetManager.open("tap_occurrence.scaled.model"))));
            holding_hand_model = svm.svm_load_model(
                    new BufferedReader(
                            new InputStreamReader(
                                    assetManager.open("hand_5p.scaled.model"))));
            lhand_location_model = svm.svm_load_model(
                    new BufferedReader(
                            new InputStreamReader(
                                    assetManager.open("location_lhand.scaled.model"))));
            rhand_location_model = svm.svm_load_model(
                    new BufferedReader(
                            new InputStreamReader(
                                    assetManager.open("location_rhand.scaled.model"))));
            Log.d(LOG_TAG, "models loaded.");
        } catch (IOException e) {
            Log.e(LOG_TAG, "models failed to load.");
            e.printStackTrace();
        }
    }

    private void loadScalers() {
        try {
            tapOccurrenceScaler = new SVMScale(this, "range_tap_occurrence");
            holdingHandScaler = new SVMScale(this, "range_hand_5p");
            lHandLocScaler = new SVMScale(this, "range_location_lhand");
            rHandLocScaler = new SVMScale(this, "range_location_rhand");
        }catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void startDetecting(View view) {
        setDetectTaps(true);
        Button startButton = (Button) findViewById(R.id.start_button);
        if (startButton != null) {
            startButton.setVisibility(View.GONE);
        }
        startListening();
    }

    protected void startListening() {

        // Initialise sensors
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor linearAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        Sensor gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        sensorListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                Sensor sensor = event.sensor;
                if (sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
                    double xValue = event.values[0];
                    double yValue = event.values[1];
                    double zValue = event.values[2];
                    // Use LinkedList
                    if (linAccSamples.size() >= 15) {
                        linAccSamples.removeFirst();
                        linAccSamples.addLast(new double[] {xValue, yValue, zValue});
                    } else {
                        linAccSamples.addLast(new double[] {xValue, yValue, zValue});
                    }
                    // make a deep copy of linAccSamples and add it to sensorValuesList
                    LinkedList<double[]> linAccSamplesCopy = new LinkedList<double[]>();
                    for (double[] reading : linAccSamples) {
                        linAccSamplesCopy.add(makeArrayCopy(reading));
                    }

                    sensorValuesList.set(0, linAccSamplesCopy);

//                     Log.d(LOG_TAG, "The size of the linacc array is " + linAccSamples.size());
                } else if (sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                    double xValue = event.values[0];
                    double yValue = event.values[1];
                    double zValue = event.values[2];
                    // Use LinkedList
                    if (gyroSamples.size() >= 15) {
                        gyroSamples.removeFirst();
                        gyroSamples.addLast(new double[]{xValue, yValue, zValue});
                    } else {
                        gyroSamples.addLast(new double[] {xValue, yValue, zValue});
                    }

                    LinkedList<double[]> gyroSamplesCopy = new LinkedList<double[]>();
                    for (double[] reading : gyroSamples) {
                        gyroSamplesCopy.add(makeArrayCopy(reading));
                    }

                    sensorValuesList.set(1, gyroSamplesCopy);

//                     Log.d(LOG_TAG, "The size of the gyro array is " + gyroSamples.size());
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {

            }
        };
        // delay_game is 20ms (50hz), delay_normal is 200ms (5hz), delay_ui is 60ms (16.7hz)
        sensorManager.registerListener(sensorListener, linearAccelerometer, SensorManager.SENSOR_DELAY_GAME);
        sensorManager.registerListener(sensorListener, gyroscope, SensorManager.SENSOR_DELAY_GAME);

        SampleProducer producer = new SampleProducer();
        SampleConsumer consumer = new SampleConsumer();
        new Thread(producer).start();
        new Thread(consumer).start();
    }

    class SampleProducer implements Runnable {
        @Override
        public void run() {
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            Log.d(LOG_TAG, "SampleProducer running!");
            try {
                while (detectTaps) {
                    // the linear accelerometer always starts ahead of the gyroscope, so need to
                    // make sure that the gyroscope isn't still outputting 0s
                    if (Arrays.equals(gyroSamples.getFirst(), new double[]{0.0, 0.0, 0.0})) {
                        continue;
                    }

                    sensorValuesQueue.put(sensorValuesList);

                    Thread.sleep(20);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    class SampleConsumer implements Runnable {
        @Override
        public void run() {
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            Log.d(LOG_TAG, "SampleConsumer running!");
            try {
                while (detectTaps) {
//                    Log.d(LOG_TAG, "SampleConsumer loop");
                    ArrayList<LinkedList<double[]>> sampleValues = sensorValuesQueue.take();
                    LinkedList<double[]> linAccSamples = sampleValues.get(0);
                    LinkedList<double[]> gyroSamples = sampleValues.get(1);

                    // First generate features based on last 300ms of sensor data
                    if (Arrays.equals(gyroSamples.getFirst(), new double[]{0.0, 0.0, 0.0})) {
                        continue;
                    }
//                    Log.d(LOG_TAG, "consuming gyrosamples: " + Arrays.toString(gyroSamples.getFirst()));

                    Features features = new Features(linAccSamples, gyroSamples);
                    String tapOccurrenceFeatures = features.getTapOccurrenceFeatures();
                    String holdingHandFeatures = features.getHoldingHandFeatures();
                    String lHandLocFeatures = features.getLHandLocFeatures();
                    String rHandLocFeatures = features.getRHandLocFeatures();

                    // Then generate scaled features
                    String tapOccurrenceInput = tapOccurrenceScaler.scale(tapOccurrenceFeatures);
                    String holdingHandInput = holdingHandScaler.scale(holdingHandFeatures);
                    String lHandLocInput = lHandLocScaler.scale(lHandLocFeatures);
                    String rHandLocInput = rHandLocScaler.scale(rHandLocFeatures);

                    // Finally get predictive probabilities
                    // [tap, no tap]
                    double[] tapOccurrenceProb = svmPredict.predict(
                            tapOccurrenceInput, tap_occurrence_model);
                    // [left hand, right hand]
                    double[] holdingHandProb = svmPredict.predict(
                            holdingHandInput, holding_hand_model);
                    // [left hand locations 1 ... 5]
                    double[] lHandProb = svmPredict.predict(
                            lHandLocInput, lhand_location_model);
                    // [right hand locations 1 ... 5]
                    double[] rHandProb = svmPredict.predict(
                            rHandLocInput, rhand_location_model);

                    // if tap occurs, calculate location
                    // p(location|tap) = P(location|hand)P(hand|tap)
                    double tapProb = tapOccurrenceProb[0];
                    if (tapProb > 0.9) {
                        double[] lHandRealProb = new double[5];
                        for (int i = 0; i < lHandProb.length; i++) {
                            // probability of location i = p(tap) * p(hand|tap) * p(loc|hand)
                            lHandRealProb[i] = tapOccurrenceProb[0] * holdingHandProb[0] * lHandProb[i];
                        }

                        double[] rHandRealProb = new double[5];
                        for (int i = 0; i < rHandProb.length; i++) {
                            rHandRealProb[i] = tapOccurrenceProb[0] * holdingHandProb[1] * rHandProb[i];
                        }

                        double[] combinedProb = ArrayUtils.addAll(lHandRealProb, rHandRealProb);

                        displayPrediction(combinedProb);
//                        Log.d(LOG_TAG, "tap probability is: " + tapProb);
//
//                        Log.d(LOG_TAG, "left hand probabilities are: " + Arrays.toString(lHandRealProb));
//                        Log.d(LOG_TAG, "right hand probabilites are: " + Arrays.toString(rHandRealProb));

                    }
                    Thread.sleep(20);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    //////////////////////////////////////Helper functions//////////////////////////////////////////

    private double[] makeArrayCopy(double[] inputArray) {
        double[] copiedArray = new double[inputArray.length];
        System.arraycopy(inputArray, 0, copiedArray, 0, inputArray.length);
        return copiedArray;
    }

    private void displayPrediction(double[] probArray) {
        final double[] combinedProb = probArray;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                double maxProb = 0;
                int maxIndex = 0;
                String hand = "";
                String location = "";
                for (int i = 0; i < combinedProb.length; i++) {
                    if (combinedProb[i] > maxProb) {
                        maxProb = combinedProb[i];
                        maxIndex = i;
                    }
                }
                // now we have highest location prob and its index
                // index 0-4 correspond to left hand
                if (maxIndex < 5) {
                    hand = "Left hand";
                } else {
                    // index 5-9 corresponds to right hand
                    hand = "Right hand";
                }

                switch (maxIndex) {
                    case 0:
                        location = "Top left";
                        break;
                    case 1:
                        location = "Top";
                        break;
                    case 2:
                        location = "Top right";
                        break;
                    case 3:
                        location = "Right";
                        break;
                    case 4:
                        location = "Bottom right";
                        break;
                    case 5:
                        location = "Top right";
                        break;
                    case 6:
                        location = "Top";
                        break;
                    case 7:
                        location = "Top left";
                        break;
                    case 8:
                        location = "Left";
                        break;
                    case 9:
                        location = "Bottom left";
                        break;
                }
                TextView locationIndicator = (TextView) findViewById(R.id.tap_location);
                TextView holdingHandIndicator = (TextView) findViewById(R.id.holding_hand);
                if (locationIndicator != null && holdingHandIndicator != null) {
                    locationIndicator.setVisibility(View.VISIBLE);
                    holdingHandIndicator.setVisibility(View.VISIBLE);
                    locationIndicator.setText(location);
                    holdingHandIndicator.setText(hand);
                }
            }
        });
    }

    private void setDetectTaps(boolean detectTaps) {
        this.detectTaps = detectTaps;
    }
}
