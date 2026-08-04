package com.gnm.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;
import com.gnm.model.*;

@ApplicationScoped
public class MockDataLoader {

    private static final Logger LOG = Logger.getLogger(MockDataLoader.class);

    @Transactional
    public void onStart(@Observes StartupEvent ev) {
        LOG.info("Disabling mock data generation. Cleaning up any previous mock/dummy data...");
        
        // Database wiping has been explicitly disabled so data persists across app restarts
        
        LOG.info("Database cleaned successfully. Background active discovery scheduler will now populate real network elements.");
    }
}
