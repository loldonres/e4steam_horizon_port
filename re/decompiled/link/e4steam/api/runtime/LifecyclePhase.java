package link.e4steam.api.runtime;

public enum LifecyclePhase {
   BOOTSTRAP,
   ADDON_INITIALIZATION,
   IDLE,
   WORLD_OPENING,
   WORLD_ACTIVE,
   DRAINING,
   SHUTTING_DOWN,
   STOPPED;
}
