package org.wolkenproject.utils;

public interface VoidCallableThrowsT<Argument, T extends Throwable> {
    public void call(Argument argument) throws T;
}
