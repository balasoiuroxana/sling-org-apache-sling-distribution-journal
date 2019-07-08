/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.distribution.journal.impl.shared;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.IOUtils;
import org.apache.sling.distribution.journal.ExceptionEventSender;
import org.apache.sling.distribution.journal.JournalAvailable;
import org.apache.sling.distribution.journal.MessagingProvider;
import org.apache.sling.distribution.journal.impl.shared.DistributionMetricsService.GaugeService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component( 
        property = EventConstants.EVENT_TOPIC + "=" + ExceptionEventSender.ERROR_TOPIC
)
public class JournalAvailableChecker implements JournalAvailable, EventHandler {
    
    private static final int MAX_RETGRY_DELAY_MS = 10000;

    // Minimal number of errors before journal is considered unavailable
    public static final int MIN_ERRORS = 2;

    private static final Logger LOG = LoggerFactory.getLogger(JournalAvailableChecker.class);


    @Reference
    Topics topics;
    
    @Reference
    MessagingProvider provider;
    
    @Reference
    DistributionMetricsService metrics;
    
    private BundleContext context;

    private volatile ServiceRegistration<JournalAvailable> reg;

    private GaugeService<Boolean> gauge;

    private ExponentialBackOff backoffRetry;
    
    private AtomicInteger numErrors;
    
    @Activate
    public void activate(BundleContext context) {
        requireNonNull(provider);
        requireNonNull(topics);
        this.context = context;
        this.numErrors = new AtomicInteger();
        gauge = metrics.createGauge(DistributionMetricsService.BASE_COMPONENT + ".journal_available", "", this::isAvailable);
        LOG.info("Started Journal availability checker service");
        startChecks();
    }

    @Deactivate
    public void deactivate() {
        gauge.close();
        unRegister();
        IOUtils.closeQuietly(this.backoffRetry);
        LOG.info("Stopped Journal availability checker service");
    }

    private void doChecks() {
        provider.assertTopic(topics.getPackageTopic());
        provider.assertTopic(topics.getDiscoveryTopic());
        provider.assertTopic(topics.getStatusTopic());
        provider.assertTopic(topics.getCommandTopic());
    }

    private void available() {
        this.backoffRetry = null;
        if (this.reg == null) {
            IOUtils.closeQuietly(this.backoffRetry);
            LOG.info("Journal is available");
            this.reg = context.registerService(JournalAvailable.class, this, null);
        }
    }
    
    private void unAvailable(Exception e) {
        String msg = "Journal is still unavailable: " + e.getMessage();
        if (LOG.isDebugEnabled()) {
            LOG.warn(msg, e);
        } else {
            LOG.warn(msg);
        }
        unRegister();
    }
    
    public boolean isAvailable() {
        return reg != null;
    }

    public void run() {
        try {
            LOG.debug("Journal checker is running");
            doChecks();
            available();
        } catch (Exception e) {
            unAvailable(e);
            throw e;
        }
    }

    private void unRegister() {
        if (this.reg != null) {
            this.reg.unregister();
            this.reg = null;
        }
    }

    @Override
    public synchronized void handleEvent(Event event) {
        String type = (String) event.getProperty(ExceptionEventSender.KEY_TYPE);
        int curNumErrors = this.numErrors.incrementAndGet();
        if (this.backoffRetry == null && curNumErrors >= MIN_ERRORS) {
            LOG.warn("Received exception event {}. Journal is considered unavailable.", type);
            unRegister();
            startChecks(); 
        } else {
            LOG.info("Received exception event {}. {} of {} errors occured.", type, this.numErrors.get(), MIN_ERRORS);
        }
    }

    private void startChecks() {
        this.backoffRetry = new ExponentialBackOff(MAX_RETGRY_DELAY_MS, this::run);
        this.numErrors.set(0);
    }
}
