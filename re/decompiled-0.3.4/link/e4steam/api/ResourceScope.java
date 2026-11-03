package link.e4steam.api;

public interface ResourceScope extends Registration {
   <T extends Registration> T own(T var1);

   int openResourceCount();
}
