package me.nageshk.omnichannel.crm.platform;

/** Told when a deal moves to another lane; implemented by the application. Implementations must not throw. */
public interface DealObserver {

    /** Called after the move is saved. */
    void laneChanged(DealLaneChange change);
}
