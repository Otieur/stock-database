package cscc43.auth;

import cscc43.model.User;

import java.util.Optional;

/**
 * Holds the currently authenticated user for the CLI session.
 */
public final class AuthContext {

    private volatile User currentUser;

    public Optional<User> currentUser() {
        return Optional.ofNullable(currentUser);
    }

    public void login(User user) {
        currentUser = user;
    }

    public void logout() {
        currentUser = null;
    }

    public User requireUser() {
        User user = currentUser;
        if (user == null) {
            throw new IllegalStateException("You must be logged in to perform this action");
        }
        return user;
    }
}
