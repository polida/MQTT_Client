package utb.fai;

import org.eclipse.paho.client.mqttv3.*;

import utb.fai.API.HumiditySensor;
import utb.fai.API.IrrigationSystem;

import java.util.Timer;
import java.util.TimerTask;

public class SoilMoistureMQTTClient implements MqttCallback {

    private MqttClient client;
    private HumiditySensor humiditySensor;
    private IrrigationSystem irrigationSystem;
    private boolean irrigationFailReported = false;
    private boolean humidityFailReported = false;

    public SoilMoistureMQTTClient(HumiditySensor sensor, IrrigationSystem irrigation) {
        this.humiditySensor = sensor;
        this.irrigationSystem = irrigation;
    }

    private void sendMessage(String topic, String message) throws MqttException {
        MqttMessage mqttMessage = new MqttMessage(message.getBytes());
        client.publish(topic, mqttMessage);
    }

    private void sendHumidity() throws MqttException {
        if (humiditySensor.hasFault()) {
            sendMessage("topic/device1/out", "fault;HUMIDITY_SENSOR");
            return;
        }
        sendMessage("topic/device1/out", "humidity;" + humiditySensor.readRAWValue());
    }

    private void startIrrigation() throws MqttException {
        Timer irrigationTimer = new Timer();
        irrigationSystem.activate();
        getStatus();

        if (irrigationTimer != null) {
            irrigationTimer.cancel();
        }

        irrigationTimer = new Timer();
        irrigationTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    stopIrrigation();
                } catch (MqttException e) {
                    e.printStackTrace();
                }
            }
        }, 30000);
    }

    private void stopIrrigation() throws MqttException {
        irrigationSystem.deactivate();
        getStatus();
    }

    private void getStatus() throws MqttException {
        if (irrigationSystem.hasFault()) {
            sendMessage("topic/device1/out", "fault;IRRIGATION_SYSTEM");
            return;
        }
        sendMessage("topic/device1/out", "status;" + (irrigationSystem.isActive() ? "irrigation_on" : "irrigation_off"));
    }

    public void checkErrors() throws MqttException {
        if (irrigationSystem.hasFault()) {
            if (!irrigationFailReported){
                sendMessage("topic/device1/out", "fault;IRRIGATION_SYSTEM");
                irrigationFailReported = true;
            }
            irrigationFailReported = true;
        }
        else{
            irrigationFailReported = false;
        }
        if (humiditySensor.hasFault()) {
            if (!humidityFailReported){
                sendMessage("topic/device1/out", "fault;HUMIDITY_SENSOR");
                humidityFailReported = true;
            }
            humidityFailReported = true;
        }
        else{
            humidityFailReported = false;
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        String payload = new String(message.getPayload());
        switch (payload) {
            case "get-humidity":
                sendHumidity();
                break;
            case "get-status":
                getStatus();
                break;
            case "start-irrigation":
                startIrrigation();
                break;
            case "stop-irrigation":
                stopIrrigation();
                break;
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        System.err.println("Connection lost: " + cause.getMessage());
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        System.out.println("Message delivery complete: " + token.getMessageId());
    }

    public void start() {
        try {
            String brokerUrl = "tcp://localhost:1883";
            client = new MqttClient(brokerUrl, MqttClient.generateClientId());
            client.setCallback(this);
            System.out.println("Connecting to broker at " + brokerUrl);
            client.connect();
            System.out.println("Successfully connected to broker.");
            client.subscribe("topic/device1/in");
            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        sendHumidity();
                    } catch (MqttException e) {
                        e.printStackTrace();
                    }
                }
            }, 0, 10000);
            Timer errorReportingTimer = new Timer();
            errorReportingTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        checkErrors();
                    } catch (MqttException e) {
                        e.printStackTrace();
                    }
                }
            }, 0, 300);
            } catch (MqttException e) {
            System.err.println("Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
