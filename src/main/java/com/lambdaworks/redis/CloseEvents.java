package com.lambdaworks.redis;

import java.util.Set;

import io.netty.util.internal.ConcurrentSet;

/**
 * Close Events Facility. Can register/unregister CloseListener and fire a closed event to all registered listeners.
 * 
 * @author <a href="mailto:mpaluch@paluch.biz">Mark Paluch</a>
 * @since 3.0
 */
class CloseEvents {
    private Set<CloseListener> listeners = new ConcurrentSet<CloseListener>();

    public void fireEventClosed(Object resource) {
        for (CloseListener listener : listeners) {
            listener.resourceClosed(resource);
        }
    }

    public void addListener(CloseListener listener) {
        listeners.add(listener);
    }

    public void removeListener(CloseListener listener) {
        listeners.remove(listener);
    }

    public interface CloseListener {
        void resourceClosed(Object resource);
    }
}
