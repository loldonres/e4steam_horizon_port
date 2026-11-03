package link.e4steam.api;

public interface Registration extends AutoCloseable {
   @Override
   void close();

   boolean isClosed();
}
