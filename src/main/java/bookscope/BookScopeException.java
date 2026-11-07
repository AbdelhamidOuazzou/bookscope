package bookscope;

/** Error reported to the user without a (partial) result. */
public class BookScopeException extends RuntimeException {
    public BookScopeException(String message) {
        super(message);
    }
}
