package org.jdownloader.extensions.antistandby;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import org.appwork.utils.JVMVersion;
import org.appwork.utils.logging2.LogSource;
import org.appwork.utils.os.CrossSystem;
import org.appwork.utils.os.CrossSystem.OperatingSystem;
import org.appwork.utils.processes.ProcessBuilderFactory;
import org.jdownloader.logging.LogController;

public class MacAntiStandBy extends Thread {
    private final AntiStandbyExtension     jdAntiStandby;
    private static final int               sleep       = 5000;
    private final AtomicReference<Process> lastProcess = new AtomicReference<Process>(null);

    public MacAntiStandBy(AntiStandbyExtension antiStandbyExtension) {
        setDaemon(true);
        setName("MacAntiStandby");
        jdAntiStandby = antiStandbyExtension;
    }

    public void run() {
        final LogSource logger = LogController.CL(MacAntiStandBy.class);
        try {
            while (jdAntiStandby.isAntiStandbyThread()) {
                enableAntiStandby(logger, jdAntiStandby.requiresAntiStandby());
                sleep(sleep);
            }
        } catch (Throwable e) {
            logger.log(e);
        } finally {
            try {
                enableAntiStandby(logger, false);
            } catch (final Throwable e) {
            } finally {
                logger.fine("JDAntiStandby: Terminated");
                logger.close();
            }
        }
    }

    private void enableAntiStandby(final LogSource logger, final boolean enabled) {
        if (enabled) {
            Process process = lastProcess.get();
            if (process != null) {
                try {
                    process.exitValue();
                } catch (IllegalThreadStateException e) {
                    return;
                }
            }
            process = createProcess(logger);
            lastProcess.set(process);
            if (process != null) {
                logger.fine("JDAntiStandby: Start");
            } else {
                logger.fine("JDAntiStandby: Failed");
            }
        } else {
            final Process process = lastProcess.getAndSet(null);
            if (process != null) {
                process.destroy();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e1) {
                }
                if (process.isAlive()) {
                    final long jvmVersion = JVMVersion.get();
                    if (jvmVersion >= JVMVersion.JAVA_1_8 && jvmVersion < JVMVersion.JAVA_19) {
                        try {
                            final Method method = process.getClass().getMethod("destroyForcibly", new Class[] {});
                            if (method != null) {
                                method.setAccessible(true);
                                method.invoke(process, new Object[] {});
                            }
                        } catch (final Throwable e) {
                            logger.log(e);
                        }
                    }
                }
                logger.fine("JDAntiStandby: Stop");
            }
        }
    }

    private Process createProcess(final LogSource logger) {
        try {
            final String[] cmd;
            if (CrossSystem.getOS().isMinimum(OperatingSystem.MAC_BIG_SUR)) {
                cmd = new String[] { "caffeinate" };
            } else {
                cmd = new String[] { "pmset", "noidle" };
            }
            final ProcessBuilder probuilder = ProcessBuilderFactory.create(cmd);
            logger.info("Call:" + Arrays.toString(cmd));
            return probuilder.start();
        } catch (IOException e) {
            logger.log(e);
        }
        return null;
    }
}
