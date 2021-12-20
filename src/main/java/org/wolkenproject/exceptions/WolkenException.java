package org.wolkenproject.exceptions;

public class WolkenException extends Exception {
    private static final long serialVersionUID = 5112431926980310096L;

    public WolkenException(String msg)
    {
        super(msg);
    }
    public WolkenException(Throwable cause)
    {
        super(cause);
    }
}
