//    jDownloader - Downloadmanager
//    Copyright (C) 2008  JD-Team support@jdownloader.org  http://jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
package org.jdownloader.launcher;

import java.io.File;
import java.util.Arrays;

import org.appwork.app.launcher.parameterparser.ParameterParser;
import org.appwork.loggingv3.LogV3;
import org.appwork.resources.AWUTheme;
import org.appwork.shutdown.ShutdownController;
import org.appwork.utils.Application;
import org.appwork.utils.Application.PauseableOutputStream;
import org.appwork.utils.IO;
import org.appwork.utils.logging2.LogSource;
import org.appwork.utils.logging2.LogSourceRedirector;
import org.appwork.utils.os.CrossSystem;
import org.appwork.utils.singleapp.AnotherInstanceRunningException;
import org.appwork.utils.singleapp.Response;
import org.appwork.utils.singleapp.ResponseListener;
import org.appwork.utils.singleapp.SingleAppInstance;
import org.appwork.utils.singleapp.SingleAppInstance.ErrorReadingResponseException;
import org.jdownloader.logging.LogController;
import org.jdownloader.updatev2.JDClassLoaderLauncher;
import org.jdownloader.updatev2.RestartController;

public class StandaloneLauncher {
    private static LogSource LOGGER;
    static {
        // Set the Loggerfactory redirector (if no other is set
        if (System.getProperty("org.appwork.LoggerFactory") == null) {
            System.setProperty("org.appwork.LoggerFactory", LogSourceRedirector.class.getName());
        }
        if (System.getProperty("java.net.preferIPv6Addresses") == null) {
            // https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/doc-files/net-properties.html
            System.setProperty("java.net.preferIPv6Addresses", "system");
        }
        org.appwork.utils.Application.setApplication(".jd_home");
        final String root = org.appwork.utils.Application.getRoot(StandaloneLauncher.class);
        LogV3.info("Application Root: " + root);// DO NOT REMOVE! this is important to have LogSystem initialized first!
        /**
         * The sorting algorithm used by java.util.Arrays.sort and (indirectly) by java.util.Collections.sort has been replaced. The new
         * sort implementation may throw an IllegalArgumentException if it detects a Comparable that violates the Comparable contract. The
         * previous implementation silently ignored such a situation. If the previous behavior is desired, you can use the new system
         * property, java.util.Arrays.useLegacyMergeSort, to restore previous mergesort behavior. Nature of Incompatibility: behavioral RFE:
         * http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6804124
         *
         * Sorting live data (values changing during sorting) violates the general contract
         *
         * java.lang.IllegalArgumentException: Comparison method violates its general contract!
         */
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        try {
            /*
             * never cache negative answers,workaround for buggy dns servers that can fail and then the cache would be polluted for cache
             * timeout
             */
            java.security.Security.setProperty("networkaddress.cache.negative.ttl", 0 + "");
        } catch (final Throwable e) {
        }
        AWUTheme.I().setNameSpace("org/jdownloader/");
        ShutdownController.getInstance().setLogger(LogController.getInstance().getLogger(ShutdownController.class.getName()));
        if (System.getProperty("syserr") != null) {
            final File file = new File(System.getProperty("syserr"));
            final PauseableOutputStream stream = Application.ERR_OUT;
            Application.addStreamCopy(file, stream);
        }
        if (System.getProperty("sysout") != null) {
            final File file = new File(System.getProperty("sysout"));
            Application.addStreamCopy(file, Application.STD_OUT);
        }
    }

    public static void main(final String[] args) throws Exception {
        LOGGER = LogController.getInstance().getLogger(StandaloneLauncher.class.getName());
        try {
            /* blacklist github.com/jaymoulin/docker-jdownloader and github.com/antlafarge/jdownloader env variables */
            Application.printSystemProperties(LOGGER, Arrays.asList(new String[] { "env_MYJD_USER", "env_MYJD_PASSWORD", "env_JD_PASSWORD", "env_JD_EMAIL" }));
        } catch (Throwable e) {
            /* update compatibility */
            Application.printSystemProperties(LOGGER);
        }
        LOGGER.info("Args: " + Arrays.toString(args));
        long t = System.currentTimeMillis();
        final ParameterParser pp = RestartController.getInstance().getParameterParser(args);
        LOGGER.info("Restart Controller init done in " + (System.currentTimeMillis() - t));
        t = System.currentTimeMillis();
        LOGGER.info("Parse Arguments");
        pp.parse(null);
        LOGGER.info("Parameter Parse done in " + (System.currentTimeMillis() - t));
        t = System.currentTimeMillis();
        LOGGER.info("Single Instance Controller");
        SingleAppInstance singleInstance = null;
        if (!pp.hasCommandSwitch("n")) {
            singleInstance = new SingleAppInstance("JD2", Application.getTemp().getParentFile(), null);
            singleInstance.setForwardMessageDirectIfNoOtherInstanceIsFound(false);
            try {
                singleInstance.start(new ResponseListener() {
                    @Override
                    public void onReceivedResponse(Response r) {
                        LOGGER.info("Received Response from existing instance: " + r);
                    }

                    @Override
                    public void onConnected(String[] message) {
                        LOGGER.info("existing jD instance found!");
                        LOGGER.info("Send parameters to existing jD instance and exit: " + Arrays.toString(args));
                    }
                }, args.length == 0 ? new String[] { "--focus" } : args);
            } catch (final AnotherInstanceRunningException e) {
                LOGGER.log(e);
                System.exit(1);
            } catch (final ErrorReadingResponseException e) {
                LOGGER.log(e);
            } catch (final Exception e) {
                LOGGER.log(e);
                LOGGER.info("Instance Handling not possible!");
            }
        }
        launchJDownloader(args, singleInstance);
    }

    public static void launchJDownloader(final String[] args, final SingleAppInstance singleInstance) throws Exception {
        try {
            if (!CrossSystem.isMac() && Application.isJared(StandaloneLauncher.class)) {
                final long pid = CrossSystem.getPID();
                if (pid >= 1) {
                    final String jarName = Application.getJarName(StandaloneLauncher.class).replaceFirst("(?i)\\.jar$", ".pid");
                    final File pidFile = Application.getResource(jarName);
                    if (!pidFile.exists() || (pidFile.isFile() && pidFile.delete())) {
                        pidFile.deleteOnExit();
                        IO.writeStringToFile(pidFile, Long.toString(pid) + "\n");
                    }
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        JDClassLoaderLauncher.updateClassPath();
        org.jdownloader.startup.Main.main(args);
        if (singleInstance != null) {
            singleInstance.setListener(org.jdownloader.startup.Main.PARAMETER_HANDLER);
        }
    }
}