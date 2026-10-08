package org.eclipse.mat.dhp.plugin;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGi Bundle Activator for the Dynamic Heap Parser (DHP).
 * Automatically intercepts and registers DhpSnapshotFactory on bundle start.
 */
public class DhpPlugin implements BundleActivator {
    private static final Logger log = LoggerFactory.getLogger(DhpPlugin.class);
    public static final String PLUGIN_ID = "org.eclipse.mat.dhp";

    private static DhpPlugin instance;

    public DhpPlugin() {
        instance = this;
    }

    public static DhpPlugin getDefault() {
        return instance;
    }

    @Override
    public void start(BundleContext context) throws Exception {
        instance = this;
        log.info("Dynamic Heap Parser (DHP) OSGi Bundle started. Installing DhpSnapshotFactory hook...");
        DhpSnapshotFactory.install();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        instance = null;
    }
}
