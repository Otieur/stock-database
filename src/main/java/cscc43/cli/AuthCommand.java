package cscc43.cli;

import cscc43.auth.AuthContext;
import cscc43.model.User;
import cscc43.service.UserService;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Commands around authentication lifecycle.
 */
@CommandLine.Command(name = "auth", description = "Authentication commands", subcommands = {
    AuthCommand.Login.class,
    AuthCommand.Logout.class,
    AuthCommand.WhoAmI.class
})
public final class AuthCommand implements Runnable {

    private final AuthContext authContext;
    private final UserService userService;

    public AuthCommand(AuthContext authContext, UserService userService) {
        this.authContext = authContext;
        this.userService = userService;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public AuthContext authContext() {
        return authContext;
    }

    public UserService userService() {
        return userService;
    }

    @CommandLine.Command(name = "login", description = "Authenticate with email and password")
    public static final class Login implements Callable<Integer> {

        @CommandLine.ParentCommand
        AuthCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--email", required = true)
        String email;

        @CommandLine.Option(names = "--password", required = true, interactive = true)
        String password;

        @Override
        public Integer call() {
            User user = parent.userService().login(email, password);
            parent.authContext().login(user);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Logged in as %s (%s)%n", user.name(), user.email());
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "logout", description = "Clear current session")
    public static final class Logout implements Callable<Integer> {

        @CommandLine.ParentCommand
        AuthCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            parent.authContext().logout();
            PrintWriter out = spec.commandLine().getOut();
            out.println("Logged out");
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "whoami", description = "Show the current user")
    public static final class WhoAmI implements Callable<Integer> {

        @CommandLine.ParentCommand
        AuthCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            Optional<User> user = parent.authContext().currentUser();
            PrintWriter out = spec.commandLine().getOut();
            if (user.isPresent()) {
                User value = user.get();
                out.printf("You are %s (%s)%n", value.name(), value.email());
            } else {
                out.println("Not logged in");
            }
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }
}
