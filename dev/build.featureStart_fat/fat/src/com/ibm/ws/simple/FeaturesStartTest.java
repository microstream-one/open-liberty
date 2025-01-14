/*******************************************************************************
 * Copyright (c) 2019 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.simple;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeMap;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import com.ibm.websphere.simplicity.OperatingSystem;
import com.ibm.websphere.simplicity.config.ServerConfiguration;
import com.ibm.websphere.simplicity.log.Log;

import componenttest.annotation.Server;
import componenttest.custom.junit.runner.FATRunner;
import componenttest.topology.impl.JavaInfo;
import componenttest.topology.impl.LibertyServer;

/**
 * Super simple test to verify that each Open Liberty
 * feature can start and stop properly.
 */
@RunWith(FATRunner.class)
public class FeaturesStartTest {

    private static final Class<?> c = FeaturesStartTest.class;

    static final Map<String, Integer> featureJavaLevels = new TreeMap<>();
    static final List<String> features = new ArrayList<>();
    static final Map<String, Set<String>> acceptableErrors = new HashMap<>();
    static final List<String> tempFeatureJavaLevels = new ArrayList<>();

    static JavaInfo javaInfo = null;
    static int JAVA_LEVEL;

    @Server("features.start.server")
    public static LibertyServer server;

    @Rule
    public TestName testName = new TestName();

    @BeforeClass
    public static void setUp() throws Exception {
        String method = "setup";

        javaInfo = JavaInfo.forServer(server);
        JAVA_LEVEL = javaInfo.majorVersion();
        Log.info(c, method, "The java level being used by the server is: " + JAVA_LEVEL);

        initAcceptableErrors();

        Properties featureProps = new Properties();
        InputStream input = new FileInputStream("lib/LibertyFATTestFiles/feature-java-levels.properties");
        featureProps.load(input);
        for (Entry<Object, Object> featureProp : featureProps.entrySet()) {
            String feature = ((String) featureProp.getKey()).toLowerCase();
            Integer minJavaLevel = Integer.valueOf((String) featureProp.getValue());
            featureJavaLevels.put(feature, minJavaLevel);
        }

        File featureDir = new File(server.getInstallRoot() + "/lib/features/");
        // If there was a problem building projects before this test runs, "lib/features" won't exist
        if (featureDir != null && featureDir.exists()) {
            for (File feature : featureDir.listFiles()) {
                parseShortName(feature);
            }
        }

        // Randomly choose half of the total features to test and just run those so we
        // don't timeout in 2 hours
        int featuresListSize = features.size();
        Log.info(c, method, "Initial feature list size from /lib/features/ = " + featuresListSize);

        // Grab some random numbers to use for which tests will run
        ArrayList<Integer> randomNumberList = randomNumberGenerator(featuresListSize, featuresListSize / 2);

        for (int i = 0; i < features.size(); i++) {
            if (randomNumberList.contains(i)) {
                tempFeatureJavaLevels.add(features.get(i));
                Log.info(c, method, "Adding test '" + features.get(i) + "' for this run.");
            } else {
                Log.info(c, method, "Ignoring test '" + features.get(i) + "' for this run.");
            }
        }

        features.clear();
        features.addAll(tempFeatureJavaLevels);
        Log.info(c, method, "Total features to be executed after randomizing and removal = " + features.size() + " : " + features.toString());
    }

    @After
    public void cleanup() throws Exception {
        if (server.isStarted()) {
            // If the server is started at this point, stop the server ignoring all messages
            // but keep the logs (because there was a failure)
            server.stopServer(".*");
        }
    }

    @Test
    public void testFeaturesStart() throws Exception {
        final String m = testName.getMethodName();
        Set<String> failingFeatures = new HashSet<String>();
        Map<String, Exception> otherFailures = new HashMap<String, Exception>();

        for (String feature : features) {

            if (skipFeature(feature))
                continue;

            Log.info(c, m, ">>>>> BEGIN " + feature);
            boolean saveLogs = true;
            try {
                setFeature(feature);
                server.startServer(feature + ".log");

                // Verify we DON'T get CWWKF0032E
                boolean featureStarted = server.findStringsInLogs("CWWKF0032E").size() == 0;
                if (!featureStarted)
                    failingFeatures.add(feature);

                // Stop server and only save logs if a feature failed to start
                Set<String> allowedErrors = acceptableErrors.get(feature);
                server.stopServer(false, allowedErrors == null ? new String[] {} : allowedErrors.toArray(new String[allowedErrors.size()]));
                saveLogs = !featureStarted;
            } catch (Exception e) {
                saveLogs = true;
                Log.error(c, m, e);
                otherFailures.put(feature, e);
            } finally {
                if (saveLogs)
                    server.postStopServerArchive();
                Log.info(c, m, "<<<<< END   " + feature);
            }
        }

        // TODO: Do these assertions for each feature start and stop attempt
        // to easily correlate error encountered with each feature.
        // So transfer to within for loop.
        assertTrue("Feature(s) " + failingFeatures + " should have started but did not.", failingFeatures.isEmpty());
        assertTrue("Features(s) " + otherFailures.keySet() + " did not start/stop cleanly on their own " +
                   "due to the following exceptions: " + otherFailures.entrySet(),
                   otherFailures.isEmpty());
    }

    public static void setFeature(String feature) throws Exception {
        ServerConfiguration config = server.getServerConfiguration();
        Set<String> features = config.getFeatureManager().getFeatures();
        features.clear();
        features.add(feature);
        server.updateServerConfiguration(config);
    }

    private static void parseShortName(File feature) throws IOException {
        // Only scan *.mf files
        if (feature.isDirectory() || !feature.getName().endsWith(".mf"))
            return;

        Scanner scanner = new Scanner(feature);
        try {
            String shortName = null;
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if (line.startsWith("IBM-ShortName:")) {
                    shortName = line.substring("IBM-ShortName:".length()).trim();
                    String upperShortName = shortName.toUpperCase();
                    if (upperShortName.contains("EECLIENT") || upperShortName.contains("SECURITYCLIENT")) {
                        Log.info(c, "parseShortName", "Skipping client-only feature: " + feature.getName());
                        return;
                    }
                } else if (line.contains("IBM-Test-Feature:") && line.contains("true")) {
                    Log.info(c, "parseShortName", "Skipping test feature: " + feature.getName());
                    return;
                } else if (line.startsWith("Subsystem-SymbolicName:") && !line.contains("visibility:=public")) {
                    Log.info(c, "parseShortName", "Skipping non-public feature: " + feature.getName());
                    return;
                }
            }
            if (shortName != null) {
                features.add(shortName);
                Log.info(c, "parseShortName", "Added public feature: " + shortName);
            } else {
                Log.info(c, "parseShortName", "Skipped feature: " + feature.getName());
            }
        } finally {
            scanner.close();
        }
    }

    private static void initAcceptableErrors() throws Exception {
        String[] QUISCE_FAILRUES = new String[] { "CWWKE1102W", "CWWKE1107W" };

        allowError("openapi-3.1", QUISCE_FAILRUES);
        allowError("mpOpenApi-1.0", QUISCE_FAILRUES);

        allowError("samlWeb-2.0", "CWWKS5207W: .* inboundPropagation"); // lets the user now certain config attributes will be ignored depending on whether or not 'inboundPropagation' is configured
        allowError("wsSecuritySaml-1.1", "CWWKS5207W: .* inboundPropagation"); // pulls in the samlWeb-2.0 feature
    }

    private boolean skipFeature(String feature) throws Exception {

        // Don't test this feature if environment is using Java level below minimum required
        // specified in properties file. Not every feature has a mapping in that file.
        if (featureJavaLevels.containsKey(feature)) {
            Integer javaLevel = featureJavaLevels.get(feature);
            if (JAVA_LEVEL < javaLevel) {
                Log.info(c, testName.getMethodName(), "Skipping " + feature + " since it needs a minimum Java level of " + javaLevel.toString());
                return true;
            }
        }

        // This feature is grandfathered in on not starting cleanly on its own. Fixing it could potentially break existing configurations
        if (feature.equalsIgnoreCase("wsSecurity-1.1"))
            return true;

        // Needs to be enabled in conjunction with spnego-1.0 or OIDC
        if (feature.equalsIgnoreCase("constrainedDelegation-1.0"))
            return true;

        if (feature.equalsIgnoreCase("logstashCollector-1.0")) {
            if (javaInfo.vendor() != JavaInfo.Vendor.IBM) {
                Log.info(c, testName.getMethodName(), "Skipping feature " + feature + " because it is for IBM JDK only.");
                return true;
            } else if (server.getMachine().getOperatingSystem().equals(OperatingSystem.ZOS)) {
                Log.info(c, testName.getMethodName(), "Skipping feature " + feature + " because the attach API is disabled on z/OS");
                return true;
            }
        }

        return false;
    }

    private static void allowError(String forFeature, String... allowedErrors) {
        if (allowedErrors == null || allowedErrors.length == 0)
            return;

        Set<String> allowedSet = acceptableErrors.get(forFeature);
        if (allowedSet == null)
            acceptableErrors.put(forFeature, allowedSet = new HashSet<String>());

        for (String allowedError : allowedErrors)
            allowedSet.add(allowedError);
    }

    /**
     * Generates a list of unique random numbers
     *
     * @param totalNumbers
     * @param numToKeep
     * @return
     */
    private static ArrayList<Integer> randomNumberGenerator(int totalNumbers, int numbersToGenerate) {
        String method = "randomNumberGenerator";
        ArrayList<Integer> numbers = new ArrayList<Integer>();

        // Build a random list of tests to delete
        int ranNum = 0;
        int counter = 0;
        Random random = new Random();
        while (counter < numbersToGenerate) {
            ranNum = random.nextInt(totalNumbers);
            if (!numbers.contains(ranNum)) {
                numbers.add(ranNum);
                counter++;
            }
        }

        Log.info(c, method, "Random numbers are = " + numbers.toString());

        Collections.sort(numbers);

        return numbers;
    }
}
