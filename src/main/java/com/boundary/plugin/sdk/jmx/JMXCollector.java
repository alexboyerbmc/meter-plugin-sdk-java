// Copyright 2015 BMC Software, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// Copyright 2014 Boundary, Inc.

package com.boundary.plugin.sdk.jmx;

import com.boundary.plugin.sdk.Collector;
import com.boundary.plugin.sdk.Event.EventSeverity;
import com.boundary.plugin.sdk.EventBuilder;
import com.boundary.plugin.sdk.EventSink;
import com.boundary.plugin.sdk.Measurement;
import com.boundary.plugin.sdk.MeasurementBuilder;
import com.boundary.plugin.sdk.MeasurementSink;
import com.boundary.plugin.sdk.PluginJSON;
import com.boundary.plugin.sdk.PluginUtil;
import com.boundary.plugin.sdk.jmx.extractor.AttributeValueExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeMBeanException;
import javax.management.openmbean.InvalidKeyException;
import java.io.IOException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.Date;

/**
 * Handles the collection of metrics from a JMX connection.
 * <p>
 * <ol>
 * <li>JMX Client connection and authentication information</li>
 * <li>MBean collection information</li>
 * </ol>
 * <p>
 * Responsibilities
 * <ol>
 * <li>Establish connection to the java virtual machine.</li>
 * <li>Handle scheduling of sampling of the MBean attributes</li>
 * </ol>
 *
 * @author davidg
 */
public class JMXCollector implements Collector {

    private static final Logger LOG = LoggerFactory.getLogger(JMXCollector.class);
    private CollectorState state;

    private JMXClient client;
    private JMXPluginConfigurationItem item;
    private JMXPlugin plugin;
    private MBeanMap mbeanMap;
    private MeasurementSink output;
    private EventSink eventOutput;
    private String name;
    private AttributeValueExtractor valueExtractor;
    private String source;
    private MBeanServerConnection mbeanServerConnection;
    public JMXCollector(JMXPlugin plugin, String name,
                        JMXPluginConfigurationItem item,
                        MBeanMap mbeanMap,
                        AttributeValueExtractor valueExtractor,
                        MeasurementSink output,
                        EventSink eventOutput) {
        this.plugin = plugin;
        this.name = name;
        this.client = new JMXClient();
        this.item = item;
        this.mbeanMap = mbeanMap;
        this.output = output;
        this.eventOutput = eventOutput;
        this.valueExtractor = valueExtractor;
        this.state = CollectorState.INITIALIZING;
    }

    @Override
    public Measurement[] getMeasures() {
        // TODO Part of the scheme to generalize collectors
        // in the framework. Does nothing for now.
        return null;
    }

    public String getName() {
        return this.name;
    }

    public void getMetricsFromAttributes(MBeanServerConnection connection, ObjectInstance instance, MBeanAttribute attr) throws InstanceNotFoundException {
        try {
            LOG.debug("object: {},attribute: {}, type: {}",
                    instance.getObjectName(), attr.getAttribute(),
                    attr.getDataType());
            Object obj = connection.getAttribute(instance.getObjectName(), attr.getAttribute());

            LOG.debug("metric: {}, object class: {}, value: {}",
                    attr.getMetricName(), obj.getClass(), obj);
            Number value = valueExtractor.getValue(obj, attr);
            MeasurementBuilder builder = new MeasurementBuilder();
            builder.setName(attr.getMetricName())
                    .setSource(this.source)
                    .setValue(value)
                    .setTimestamp(null);
            Measurement m = builder.build();

            // Sends to configured {@link MeasureWriter}
            this.output.send(m);

        } catch (ReflectionException | IOException re) {
            LOG.error("Reflection exception occurred while getting attribute {} from {}",
                    attr.getAttribute(), instance.getObjectName());
        } catch (AttributeNotFoundException nf) {
            LOG.warn("AttributeNotFoundException exception occurred while getting attribute {} from {}.",
                    attr.getAttribute(), instance.getObjectName());
        } catch (MBeanException m) {
            LOG.error("MBeanException exception occurred while getting attribute {} from {}",
                    attr.getAttribute(), instance.getObjectName());
        } catch (RuntimeMBeanException rt) {
            LOG.error("RuntimeMBeanException exception occurred while getting attribute {} from {}: {}",
                    attr.getAttribute(), instance.getObjectName(), rt.getMessage());
        } catch (InvalidKeyException ik) {
            LOG.error("InvalidKeyException exception occurred while getting attribute {} from {}",
                    attr.getAttribute(), instance.getObjectName());
        } catch (NullPointerException np) {
            LOG.warn("Null value for attribute {} for {}",
                    attr.getAttribute(), instance.getObjectName());
        } catch (NumberFormatException nfe) {
            LOG.warn("Unable to convert attribute {} value to {} for {}. ",
                    attr.getAttribute(), attr.getDataType(), instance.getObjectName());
        } catch (UnsupportedOperationException o) {
            LOG.warn("UnsupportedOperationException while getting attribute {} for {}",
                    attr.getAttribute(), instance.getObjectName());
        }
    }

    /**
     * Fetches an MBean attributes and then collects metrics
     *
     * @param entry {@link MBeanEntry}
     */
    private void queryMBean(MBeanEntry entry) throws IOException {
        try {
            ObjectName name = new ObjectName(entry.getMbean());
            ObjectInstance instance = this.mbeanServerConnection.getObjectInstance(name);
            for (MBeanAttribute attr : entry.getAttributes()) {
                if (attr.isEnabled()) {
                    getMetricsFromAttributes(this.mbeanServerConnection, instance, attr);
                }
            }
        } catch (MalformedObjectNameException o) {
            LOG.error("MalformedObjectNameException for MBean: {}", entry.getMbean());
        } catch (InstanceNotFoundException i) {
            LOG.error("InstanceNotFoundException for MBean: {}", entry.getMbean());
        }
    }

    /**
     * @return {@link CollectorState}
     */
    private CollectorState stateInitializing() {
        // Used the source specified in the configuration or use the
        // host name as the default
        if (item.getSource() == null) {
            this.source = PluginUtil.getHostname();
        } else {
            this.source = item.getSource();
        }
        return CollectorState.CONNECTING;
    }

    private EventBuilder getEventBuilder() {
        EventBuilder builder = new EventBuilder();
        final PluginJSON manifest = plugin.getManifest();
        builder.setTitle(String.format("Plugin %s version %s", manifest.getName(), manifest.getVersion()));
        builder.setSource(this.source);
        builder.setHost(PluginUtil.getHostname());
        return builder;
    }

    private void emitErrorEvent(final String message) {
        emitEvent(EventSeverity.ERROR, message);
    }

    private void emitEvent(final EventSeverity severity, final String message) {
        EventBuilder builder = getEventBuilder();
        builder.setSeverity(severity);
        builder.setMessage(message);
        eventOutput.emit(builder.build());
    }

    private CollectorState stateConnecting() {
        CollectorState nextState = CollectorState.CONNECTING;

        // Loop trying to establish a connection to the MBean server
        while (nextState == CollectorState.CONNECTING) {
            try {
                if (item.getUser() != null && item.getPassword() != null) {
                    client.connect(item.getHost(), item.getPort(), item.getUser(), item.getPassword());
                } else {
                    client.connect(item.getHost(), item.getPort());
                }
                this.mbeanServerConnection = client.getMBeanServerConnection();
                if (this.mbeanServerConnection == null) {
                    LOG.error("Collector: {}, MBean Server Connection is null for {}",
                            this.getName());
                    client.disconnect();
                } else {
                    nextState = CollectorState.CONNECTED;
                }
            } catch (UnknownHostException e) {
                final String message = String.format("Collector %s, Unknown host %s, port %d",
                        this.getName(), item.getHost(), item.getPort());
                LOG.error(message);
                emitErrorEvent(message);
            } catch (NoRouteToHostException e) {
                final String message = String.format("Collector %s, No route to host %s, port %d", this.getName(), item.getHost(), item.getPort());
                LOG.error(message);
                emitErrorEvent(message);
            } catch (IOException e) {
                final String message = String.format("Collector %s, Failed to connect MBeanServer at host %s, port %d", this.getName(), item.getHost(), item.getPort());
                LOG.error(message);
                emitErrorEvent(message);
            }
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                // TODO: this is bad. we should exit out on an interrupt
                // and make sure that Thread.currentThread().interrupt() is called
                // to preserve the interrupt.
                // if the intent here is to retry connections, we also need an upper bound on number of tries.
                // probably right thing here is set the state = CollectorState.EXIT and preserve interrupt
                e.printStackTrace();
            }
        }
        return nextState;
    }

    private CollectorState stateConnected() {
        return CollectorState.COLLECTING;
    }

    private CollectorState stateCollecting() {
        CollectorState nextState = CollectorState.DISCONNECTED;

        // Continuously loop until the thread is interrupted
        while (nextState != CollectorState.TERMINATED) {
            try {
                long start = new Date().getTime();
                for (MBeanEntry entry : mbeanMap.getMap()) {
                    if (entry.isEnabled()) {
                        queryMBean(entry);
                    }
                }
                // TBD: How to handle if the sample time is longer
                // than the amount of time it takes to collect the metrics.
                long stop = new Date().getTime();
                long delta = stop - start;
                delta = delta < 0 ? 0 : delta;
                long timeToSleep = Long.parseLong(item.getPollInterval()) - delta;
                if (timeToSleep > 0) {
                    Thread.sleep(timeToSleep);
                }
            } catch (IOException i) {
                LOG.debug("Collector {}, Received IOException: {}", this.getName(), i.getMessage());
                LOG.warn("Collector {}, Received IOException: {}", this.getName());
                nextState = CollectorState.DISCONNECTED;
                break;
            } catch (InterruptedException e) {
                LOG.warn("Processing thread {} interrupted", Thread.currentThread().getName());
                nextState = CollectorState.TERMINATED;
            }
        }
        return nextState;
    }

    private CollectorState stateDisconnected() {
        // Disconnect our client, so that we can attempt reconnect
        client.disconnect();
        return CollectorState.CONNECTING;
    }

    private CollectorState stateTerminated() {
        return CollectorState.EXIT;
    }

    /**
     * This is our main processing loop that is run by a separate thread.
     * <p>
     * All exceptions and retry cases need to be handled in this loop.
     */
    @Override
    public void run() {

        do {
            LOG.info("Collector {} state is {}", name, state);
            switch (this.state) {
                case INITIALIZING:
                    this.state = stateInitializing();
                    break;
                case CONNECTING:
                    this.state = stateConnecting();
                    break;
                case CONNECTED:
                    this.state = stateConnected();
                    break;
                case COLLECTING:
                    this.state = stateCollecting();
                    break;
                case DISCONNECTED:
                    this.state = stateDisconnected();
                    break;
                case TERMINATED:
                    this.state = stateTerminated();
                    break;
                case EXIT:
                    break;
            }
        } while (this.state != CollectorState.EXIT);

        System.out.println("complete");
    }

    /**
     * Defines the states of our state machine
     */
    private enum CollectorState {
        INITIALIZING,
        CONNECTING,
        CONNECTED,
        COLLECTING,
        DISCONNECTED,
        TERMINATED,
        EXIT
    }
}
