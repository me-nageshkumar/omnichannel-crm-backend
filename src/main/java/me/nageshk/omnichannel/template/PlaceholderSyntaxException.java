package me.nageshk.omnichannel.template;

/** Template text contains a placeholder the grammar does not allow. */
public class PlaceholderSyntaxException extends RuntimeException {

    public PlaceholderSyntaxException(String message) {
        super(message);
    }
}
