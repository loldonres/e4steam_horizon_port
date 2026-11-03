package com.codedisaster.steamworks;

import java.nio.ByteBuffer;

public class SteamHTTP extends SteamInterface {
   private final boolean isServer;

   public SteamHTTP(SteamHTTPCallback callback) {
      this(false, SteamHTTPNative.createCallback(new SteamHTTPCallbackAdapter(callback)));
   }

   SteamHTTP(boolean isServer, long callback) {
      super(callback);
      this.isServer = isServer;
   }

   public SteamHTTPRequestHandle createHTTPRequest(SteamHTTP.HTTPMethod requestMethod, String absoluteURL) {
      return new SteamHTTPRequestHandle(SteamHTTPNative.createHTTPRequest(this.isServer, requestMethod.ordinal(), absoluteURL));
   }

   public boolean setHTTPRequestContextValue(SteamHTTPRequestHandle request, long contextValue) {
      return SteamHTTPNative.setHTTPRequestContextValue(this.isServer, request.handle, contextValue);
   }

   public boolean setHTTPRequestNetworkActivityTimeout(SteamHTTPRequestHandle request, int timeoutSeconds) {
      return SteamHTTPNative.setHTTPRequestNetworkActivityTimeout(this.isServer, request.handle, timeoutSeconds);
   }

   public boolean setHTTPRequestHeaderValue(SteamHTTPRequestHandle request, String headerName, String headerValue) {
      return SteamHTTPNative.setHTTPRequestHeaderValue(this.isServer, request.handle, headerName, headerValue);
   }

   public boolean setHTTPRequestGetOrPostParameter(SteamHTTPRequestHandle request, String paramName, String paramValue) {
      return SteamHTTPNative.setHTTPRequestGetOrPostParameter(this.isServer, request.handle, paramName, paramValue);
   }

   public SteamAPICall sendHTTPRequest(SteamHTTPRequestHandle request) {
      return new SteamAPICall(SteamHTTPNative.sendHTTPRequest(this.isServer, this.callback, request.handle));
   }

   public SteamAPICall sendHTTPRequestAndStreamResponse(SteamHTTPRequestHandle request) {
      return new SteamAPICall(SteamHTTPNative.sendHTTPRequestAndStreamResponse(this.isServer, request.handle));
   }

   public int getHTTPResponseHeaderSize(SteamHTTPRequestHandle request, String headerName) {
      return SteamHTTPNative.getHTTPResponseHeaderSize(this.isServer, request.handle, headerName);
   }

   public boolean getHTTPResponseHeaderValue(SteamHTTPRequestHandle request, String headerName, ByteBuffer value) throws SteamException {
      if (!value.isDirect()) {
         throw new SteamException("Direct buffer required!");
      } else {
         return SteamHTTPNative.getHTTPResponseHeaderValue(this.isServer, request.handle, headerName, value, value.position(), value.remaining());
      }
   }

   public int getHTTPResponseBodySize(SteamHTTPRequestHandle request) {
      return SteamHTTPNative.getHTTPResponseBodySize(this.isServer, request.handle);
   }

   public boolean getHTTPResponseBodyData(SteamHTTPRequestHandle request, ByteBuffer data) throws SteamException {
      if (!data.isDirect()) {
         throw new SteamException("Direct buffer required!");
      } else {
         return SteamHTTPNative.getHTTPResponseBodyData(this.isServer, request.handle, data, data.position(), data.remaining());
      }
   }

   public boolean getHTTPStreamingResponseBodyData(SteamHTTPRequestHandle request, int bodyDataOffset, ByteBuffer data) throws SteamException {
      if (!data.isDirect()) {
         throw new SteamException("Direct buffer required!");
      } else {
         return SteamHTTPNative.getHTTPStreamingResponseBodyData(this.isServer, request.handle, bodyDataOffset, data, data.position(), data.remaining());
      }
   }

   public boolean releaseHTTPRequest(SteamHTTPRequestHandle request) {
      return SteamHTTPNative.releaseHTTPRequest(this.isServer, request.handle);
   }

   public static enum HTTPMethod {
      Invalid,
      GET,
      HEAD,
      POST,
      PUT,
      DELETE,
      OPTIONS;
   }

   public static enum HTTPStatusCode {
      Invalid(0),
      Continue(100),
      SwitchingProtocols(101),
      OK(200),
      Created(201),
      Accepted(202),
      NonAuthoritative(203),
      NoContent(204),
      ResetContent(205),
      PartialContent(206),
      MultipleChoices(300),
      MovedPermanently(301),
      Found(302),
      SeeOther(303),
      NotModified(304),
      UseProxy(305),
      TemporaryRedirect(307),
      PermanentRedirect(308),
      BadRequest(400),
      Unauthorized(401),
      PaymentRequired(402),
      Forbidden(403),
      NotFound(404),
      MethodNotAllowed(405),
      NotAcceptable(406),
      ProxyAuthRequired(407),
      RequestTimeout(408),
      Conflict(409),
      Gone(410),
      LengthRequired(411),
      PreconditionFailed(412),
      RequestEntityTooLarge(413),
      RequestURITooLong(414),
      UnsupportedMediaType(415),
      RequestedRangeNotSatisfiable(416),
      ExpectationFailed(417),
      Unknown4xx(418),
      TooManyRequests(429),
      InternalServerError(500),
      NotImplemented(501),
      BadGateway(502),
      ServiceUnavailable(503),
      GatewayTimeout(504),
      HTTPVersionNotSupported(505),
      Unknown5xx(599);

      private final int code;
      private static final SteamHTTP.HTTPStatusCode[] values = values();

      private HTTPStatusCode(int code) {
         this.code = code;
      }

      static SteamHTTP.HTTPStatusCode byValue(int statusCode) {
         int from = 0;
         int to = values.length - 1;

         while (from <= to) {
            int idx = (from + to) / 2;
            SteamHTTP.HTTPStatusCode value = values[idx];
            if (statusCode < value.code) {
               to = idx - 1;
            } else {
               if (statusCode <= value.code) {
                  return value;
               }

               from = idx + 1;
            }
         }

         return Invalid;
      }
   }
}
