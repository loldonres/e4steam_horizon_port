package link.e4steam.api.event;

public interface EventListener<T extends ApiEvent> {
   void onEvent(T var1) throws Exception;
}
