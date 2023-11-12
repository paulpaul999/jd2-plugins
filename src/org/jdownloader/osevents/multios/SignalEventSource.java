package org.jdownloader.osevents.multios;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;

import org.appwork.utils.ReflectionUtils;
import org.appwork.utils.logging2.LogInterface;
import org.appwork.utils.logging2.LogSource;
import org.appwork.utils.os.CrossSystem;
import org.jdownloader.logging.LogController;

public abstract class SignalEventSource {
    private final LogSource               logger;
    private final HashMap<String, Object> oldHandlers    = new HashMap<String, Object>();
    private final HashSet<String>         ignoredSignals = new HashSet<String>();
    private final Object                  signalHandler;

    /**
     * https://blogs.oracle.com/javamagazine/post/a-peek-into-java-17-continuing-the-drive-to-encapsulate-the-java-runtime-internals
     *
     * @throws Exception
     */
    public SignalEventSource() throws Exception {
        // https://blogs.oracle.com/javamagazine/post/a-peek-into-java-17-continuing-the-drive-to-encapsulate-the-java-runtime-internals
        logger = LogController.getInstance().getLogger(SignalEventSource.class.getName());
        signalHandler = Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] { Class.forName("sun.misc.SignalHandler") }, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (method.getName().endsWith("handle")) {
                    handle(args[0]);
                    return null;
                } else {
                    return method.invoke(this, args);
                }
            }
        });
        init();
    }

    protected void init() throws Exception {
        boolean regFlag = reg("INT");
        regFlag |= reg("TERM");
        if (!CrossSystem.isWindows()) {
            // HUP not available on windows
            regFlag |= reg("HUP");
        }
        if (!regFlag) {
            throw new Exception("could not register INT,TERM or HUP signal");
        }
    }

    protected LogInterface getLogger() {
        return logger;
    }

    public boolean setIgnore(final String signalName, final boolean ignore) {
        try {
            final Class<?> signalClass = Class.forName("sun.misc.Signal");
            final String sigName = ReflectionUtils.invoke(signalClass, "getName", signalClass.getConstructor(String.class).newInstance(signalName), String.class);
            final boolean ret;
            synchronized (ignoredSignals) {
                if (ignore) {
                    ret = ignoredSignals.add(sigName);
                } else {
                    ret = ignoredSignals.remove(sigName);
                }
            }
            getLogger().info("ignore(" + ignore + " signal: " + signalName + "->" + sigName + "=" + ret);
            return ret;
        } catch (Throwable e) {
            getLogger().exception("failed to change signal ignore:" + signalName + "|" + ignore, e);
            return false;
        }
    }

    protected boolean reg(final String signalName) {
        try {
            final Class<?> signalClass = Class.forName("sun.misc.Signal");
            final Object signal = signalClass.getConstructor(String.class).newInstance(signalName);
            synchronized (oldHandlers) {
                oldHandlers.put(signalName, ReflectionUtils.invoke(signalClass, null, "handle", signal, void.class, new Class[] { signalClass, Class.forName("sun.misc.SignalHandler") }, signal, signalHandler));
            }
            getLogger().info("register signal: " + signalName + "->" + signal);
            return true;
        } catch (Throwable e) {
            getLogger().exception("failed to register signal" + signalName, e);
            return false;
        }
    }

    protected void handle(final Object signal) {
        try {
            final String sigName = ReflectionUtils.invoke(signal.getClass(), "getName", signal, String.class);
            final Number sigNumber = ReflectionUtils.invoke(signal.getClass(), "getNumber", signal, Number.class);
            final boolean ignored;
            synchronized (ignoredSignals) {
                ignored = ignoredSignals.contains(sigName);
            }
            if (ignored) {
                getLogger().info("Signal handler ignored for signal:" + sigName + "|" + sigNumber);
            } else {
                getLogger().info("Signal handler called for signal:" + sigName + "|" + sigNumber);
                boolean invokeOldHandler = true;
                try {
                    invokeOldHandler = onSignal(sigName, sigNumber.intValue());
                } finally {
                    final Object oldHandler;
                    synchronized (oldHandlers) {
                        oldHandler = oldHandlers.get(sigName);
                    }
                    final Class<?> signalHandlerClass = Class.forName("sun.misc.SignalHandler");
                    final Object SIG_DFL = ReflectionUtils.getFieldValue(signalHandlerClass, "SIG_DFL", null, signalHandlerClass);
                    final Object SIG_IGN = ReflectionUtils.getFieldValue(signalHandlerClass, "SIG_IGN", null, signalHandlerClass);
                    if (invokeOldHandler && oldHandler != null && oldHandler != SIG_DFL && oldHandler != SIG_IGN) {
                        // Chain back to previous handler, if one exists
                        ReflectionUtils.invoke(signalHandlerClass, "handle", oldHandler, void.class, signal);
                    }
                }
            }
        } catch (Exception e) {
            getLogger().exception("failed to handle signal:" + signal, e);
        }
    }

    public abstract boolean onSignal(String name, int number);
}
