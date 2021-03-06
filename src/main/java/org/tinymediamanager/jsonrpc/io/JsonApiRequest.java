/*
 *      Copyright (C) 2005-2015 Team XBMC
 *      http://xbmc.org
 *
 *  This Program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2, or (at your option)
 *  any later version.
 *
 *  This Program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with XBMC Remote; see the file license.  If not, write to
 *  the Free Software Foundation, 675 Mass Ave, Cambridge, MA 02139, USA.
 *  http://www.gnu.org/copyleft/gpl.html
 *
 */

package org.tinymediamanager.jsonrpc.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;

import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ObjectNode;
import org.codehaus.jackson.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tinymediamanager.jsonrpc.config.HostConfig;

/**
 * Performs HTTP POST requests on the XBMC JSON API and handles the parsing from and to {@link ObjectNode}.
 * <p/>
 * <i>Note</i>: All in here is synchronous.
 *
 * @author Joel Stemmer <stemmertech@gmail.com>
 * @author freezy <freezy@xbmc.org>
 */
public class JsonApiRequest {

  private static final Logger       LOGGER          = LoggerFactory.getLogger(JsonApiRequest.class);
  private static final String       TAG             = JsonApiRequest.class.getSimpleName();
  private static final int          REQUEST_TIMEOUT = 5000;                                         // 5 sec
  private static final ObjectMapper OM              = new ObjectMapper();

  /**
   * Executes a POST request to the URL using the JSON Object as request body and returns a JSON Object if the response was successful.
   *
   * @param url
   *          Complete URL with schema, host, port if not default and path.
   * @param entity
   *          Object being serialized as message body
   * @return JSON Object of the JSON-RPC response.
   * @throws ApiException
   */
  public static ObjectNode execute(HostConfig config, ObjectNode entity) throws ApiException {
    String response = postRequest(config, entity.toString());
    LOGGER.debug("Response: {}", response);
    return parseResponse(response);
  }

  /**
   * Execute a POST request on URL using entity as request body.
   *
   * @param url
   * @param entity
   * @return The response as a string
   * @throws ApiException
   * @throws IOException
   */
  private static String postRequest(HostConfig config, String entity) throws ApiException {
    try {
      URL url = new URL("http", config.getAddress(), config.getHttpPort(), "/jsonrpc");
      final HttpURLConnection conn = (HttpURLConnection) url.openConnection();

      conn.setRequestMethod("POST");

      // http basic authorization
      if (config.getUsername() != null && !config.getUsername().isEmpty() && config.getPassword() != null && !config.getPassword().isEmpty()) {
        final String token = Base64.encodeToString((config.getUsername() + ":" + config.getPassword()).getBytes(), false);
        conn.setRequestProperty("Authorization", "Basic " + token);
      }
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setRequestProperty("User-Agent", buildUserAgent());

      conn.setConnectTimeout(REQUEST_TIMEOUT);
      conn.setReadTimeout(REQUEST_TIMEOUT);

      conn.setDoOutput(true);

      LOGGER.debug("CALL: {}", entity.toString());
      try {
        OutputStreamWriter output = new OutputStreamWriter(conn.getOutputStream(), "UTF-8");
        output.write(entity);
        output.close();
      }
      catch (UnsupportedEncodingException e) {
        throw new ApiException(ApiException.UNSUPPORTED_ENCODING, "Unable to convert request to UTF-8", e);
      }

      StringBuilder response = new StringBuilder();
      BufferedReader reader = null;

      final int code = conn.getResponseCode();
      if (code == 200) {
        try {
          reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"), 8192);
          String line;
          while ((line = reader.readLine()) != null) {
            response.append(line);
          }
        }
        catch (UnsupportedEncodingException e) {
          throw new ApiException(ApiException.UNSUPPORTED_ENCODING, "Unable to convert HTTP response to UTF-8", e);
        }
        finally {
          if (reader != null) {
            reader.close();
          }
        }

        return response.toString();

      }
      else {
        switch (code) {
          case 400:
            throw new ApiException(ApiException.HTTP_BAD_REQUEST, "Server says \"400 Bad HTTP request\".");
          case 401:
            throw new ApiException(ApiException.HTTP_UNAUTHORIZED, "Server says \"401 Unauthorized\".");
          case 403:
            throw new ApiException(ApiException.HTTP_FORBIDDEN, "Server says \"403 Forbidden\".");
          case 404:
            throw new ApiException(ApiException.HTTP_NOT_FOUND, "Server says \"404 Not Found\".");
          default:
            if (code >= 100 && code < 200) {
              throw new ApiException(ApiException.HTTP_INFO, "Server returned informational code " + code + " instead of 200.");
            }
            else if (code >= 200 && code < 300) {
              throw new ApiException(ApiException.HTTP_SUCCESS, "Server returned success code " + code + " instead of 200.");
            }
            else if (code >= 300 && code < 400) {
              throw new ApiException(ApiException.HTTP_REDIRECTION, "Server returned redirection code " + code + " instead of 200.");
            }
            else if (code >= 400 && code < 500) {
              throw new ApiException(ApiException.HTTP_CLIENT_ERROR, "Server returned client error " + code + ".");
            }
            else if (code >= 500 && code < 600) {
              throw new ApiException(ApiException.HTTP_SERVER_ERROR, "Server returned server error " + code + ".");
            }
            else {
              throw new ApiException(ApiException.HTTP_UNKNOWN, "Server returned unspecified code " + code + ".");
            }
        }
      }
    }
    catch (MalformedURLException e) {
      throw new ApiException(ApiException.MALFORMED_URL, e.getMessage(), e);
    }
    catch (SocketTimeoutException e) {
      throw new ApiException(ApiException.IO_SOCKETTIMEOUT, e.getMessage(), e);
    }
    catch (ConnectException e) {
      throw new ApiException(ApiException.IO_EXCEPTION_WHILE_OPENING, e.getMessage(), e);
    }
    catch (IOException e) {
      throw new ApiException(ApiException.IO_EXCEPTION, e.getMessage(), e);
    }
  }

  /**
   * Parses the JSON response string and returns a {@link ObjectNode}.
   *
   * If the response is not valid JSON, contained an error message or did not include a result then a HandlerException is thrown.
   *
   * @param response
   * @return ObjectNode Root node of the server response, unserialized as ObjectNode.
   * @throws ApiException
   */
  private static ObjectNode parseResponse(String response) throws ApiException {
    try {
      final ObjectNode node = (ObjectNode) OM.readTree(response.toString());

      if (node.has("error")) {
        if (node.get("error").isTextual()) {
          final TextNode error = (TextNode) node.get("error");
          throw new ApiException(ApiException.API_ERROR, "Error: " + error.getTextValue(), null);
        }
        else {
          final ObjectNode error = (ObjectNode) node.get("error");
          throw new ApiException(ApiException.API_ERROR, "Error " + error.get("code").getIntValue() + ": " + error.get("message").getTextValue(),
              null);
        }
      }

      if (!node.has("result")) {
        throw new ApiException(ApiException.RESPONSE_ERROR, "Neither result nor error object found in response.", null);
      }

      if (node.get("result").isNull()) {
        return null;
      }

      return node;
    }
    catch (JsonProcessingException e) {
      throw new ApiException(ApiException.JSON_EXCEPTION, "Parse error: " + e.getMessage(), e);
    }
    catch (IOException e) {
      throw new ApiException(ApiException.JSON_EXCEPTION, "Parse error: " + e.getMessage(), e);
    }
  }

  /**
   * Build user agent used for the HTTP requests
   *
   * TODO: include version information
   *
   * @return String containing the user agent
   */
  private static String buildUserAgent() {
    return "tinyMediaManager-jsonrpclib-kodi";
  }
}
