package org.jdownloader.startup;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.appwork.app.launcher.parameterparser.CommandSwitch;
import org.appwork.app.launcher.parameterparser.ParameterParser;
import org.appwork.utils.Application;
import org.appwork.utils.logging2.LogSource;
import org.appwork.utils.singleapp.InstanceMessageListener;
import org.appwork.utils.singleapp.Response;
import org.appwork.utils.singleapp.ResponseSender;
import org.jdownloader.logging.LogController;
import org.jdownloader.startup.commands.AbstractStartupCommand;
import org.jdownloader.startup.commands.AddContainerCommand;
import org.jdownloader.startup.commands.AddExtractionPasswordsCommand;
import org.jdownloader.startup.commands.AddLinkCommand;
import org.jdownloader.startup.commands.BRDebugCommand;
import org.jdownloader.startup.commands.DisableSysErr;
import org.jdownloader.startup.commands.FileCommand;
import org.jdownloader.startup.commands.GuiFocusCommand;
import org.jdownloader.startup.commands.GuiMinimizeCommand;
import org.jdownloader.startup.commands.HelpCommand;
import org.jdownloader.startup.commands.JACShowCommand;
import org.jdownloader.startup.commands.MyJDownloaderCommand;
import org.jdownloader.startup.commands.ReScanPluginsCommand;
import org.jdownloader.startup.commands.ReconnectCommand;
import org.jdownloader.startup.commands.SetConfigCommand;
import org.jdownloader.startup.commands.ThreadDump;
import org.jdownloader.updatev2.RestartController;

import jd.SecondLevelLaunch;

public class ParameterHandler implements InstanceMessageListener {
    private final HashMap<String, StartupCommand> commandMap;
    private final LogSource                       logger;
    private final ArrayList<StartupCommand>       commands;

    public ParameterHandler() {
        logger = LogController.getInstance().getLogger("StartupParameterHandler");
        commandMap = new HashMap<String, StartupCommand>();
        commands = new ArrayList<StartupCommand>();
        addCommand(new AddContainerCommand());
        addCommand(new AddExtractionPasswordsCommand());
        addCommand(new AddLinkCommand());
        //
        addCommand(new GuiFocusCommand());
        addCommand(new GuiMinimizeCommand());
        addCommand(new HelpCommand(this));
        addCommand(new JACShowCommand());
        addCommand(new ReconnectCommand());
        addCommand(new FileCommand());
        addCommand(new BRDebugCommand());
        addCommand(new ReScanPluginsCommand());
        addCommand(new MyJDownloaderCommand());
        addCommand(new DisableSysErr());
        addCommand(new SetConfigCommand());
        addCommand(new ThreadDump());
        addCommand(new AbstractStartupCommand("n") {
            @Override
            public void run(String command, String... parameters) {
            }

            @Override
            public String getDescription() {
                return "Force a new Instance.";
            }
        });
        addCommand(new AbstractStartupCommand("console") {
            @Override
            public void run(String command, String... parameters) {
            }

            @Override
            public String getDescription() {
                return "Write all Logs to STDOUt or STDERR";
            }
        });
    }

    private void addCommand(StartupCommand helpCommand) {
        for (String s : helpCommand.getCommandSwitches()) {
            if (commandMap.containsKey(s)) {
                throw new IllegalStateException("Command " + s + " already is used");
            }
            commandMap.put(s, helpCommand);
        }
        commands.add(helpCommand);
    }

    @Override
    @Deprecated
    /** kept to avoid JDownloader.jar <-> Core.jar update compatibility issues. can be removed in future **/
    public void parseMessage(String[] args) {
        onIncommingMessage(null, args);
    }

    protected void execute(ParameterParser pp, boolean startup) {
        for (CommandSwitch cmd : pp.getList()) {
            StartupCommand command = commandMap.get(cmd.getSwitchCommand());
            if (command != null && (command.isRunningInstanceEnabled() || startup)) {
                command.run(cmd.getSwitchCommand(), cmd.getParameters());
            } else {
                logger.warning("Invalid Command: " + cmd.getSwitchCommand() + " - " + Arrays.toString(cmd.getParameters()));
            }
        }
    }

    public List<StartupCommand> getCommands() {
        return commands;
    }

    public void onStartup(String[] args) {
        logger.info("Startup: " + Arrays.toString(args));
        final ParameterParser startupParameters = RestartController.getInstance().getParameterParser(args);
        try {
            startupParameters.parse((File) null);
        } catch (Throwable e) {
            logger.log(e);
        }
        execute(startupParameters, true);
        if (!startupParameters.hasCommandSwitch("console") && Application.isJared(SecondLevelLaunch.class)) {
            logger.info("Remove ConsoleHandler");
            LogController.getInstance().removeConsoleHandler();
        }
    }

    @Override
    public void onIncommingMessage(ResponseSender callback, String[] message) {
        logger.info("Incomming Message: " + Arrays.toString(message));
        final ParameterParser pp = new ParameterParser(message);
        try {
            pp.parse((File) null);
        } catch (Throwable e) {
            logger.log(e);
        }
        execute(pp, false);
        if (callback != null) {
            try {
                callback.sendResponse(new Response("PONG", "Received Message"));
            } catch (Exception e) {
                logger.log(e);
            }
        }
    }
}
