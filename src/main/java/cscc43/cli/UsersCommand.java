package cscc43.cli;

import cscc43.model.User;
import cscc43.service.UserService;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Commands related to user lifecycle operations.
 */
@CommandLine.Command(name = "users", description = "Manage users", subcommands = {
    UsersCommand.Register.class,
    UsersCommand.ListUsers.class
})
public final class UsersCommand implements Runnable {

    private final UserService userService;

    public UsersCommand(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public UserService userService() {
        return userService;
    }

    @CommandLine.Command(name = "register", description = "Register a new user")
    public static final class Register implements Callable<Integer> {

        @CommandLine.ParentCommand
        UsersCommand parent;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--name", description = "Display name", required = true)
        String name;

        @CommandLine.Option(names = "--email", description = "Email address", required = true)
        String email;

        @CommandLine.Option(
            names = "--password",
            description = "Plaintext password",
            required = true,
            interactive = true,
            arity = "0..1")
        String password;

        @Override
        public Integer call() {
            UserService userService = parent.userService();
            User user = userService.register(name, email, password);
            PrintWriter out = spec.commandLine().getOut();
            out.printf("Registered user %s (%s) with id %d%n", user.name(), user.email(), user.id());
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }

    @CommandLine.Command(name = "list", description = "List registered users")
    public static final class ListUsers implements Callable<Integer> {

        @CommandLine.ParentCommand
        UsersCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            List<User> users = parent.userService().list();
            PrintWriter out = spec.commandLine().getOut();
            if (users.isEmpty()) {
                out.println("No users registered");
            } else {
                out.println("Registered users:");
                for (User user : users) {
                    out.printf("- %d | %s | %s | %s%n", user.id(), user.name(), user.email(), user.createdAt());
                }
            }
            out.flush();
            return CommandLine.ExitCode.OK;
        }
    }
}
