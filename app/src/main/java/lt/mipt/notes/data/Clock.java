package lt.mipt.notes.data;

/**
 * Time source abstraction to keep storage timestamps deterministic in tests.
 */
public interface Clock {
    Clock SYSTEM = System::currentTimeMillis;

    long nowMillis();
}
