/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.util;

import com.google.common.base.Charsets;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;

/**
 * HTTP POST things to a commonly used endpoint.
 */
public class HttpEndpoint {

  public static final int DEFAULT_COMMON_TIMEOUT_MS = 5000;

  private URL url;
  private int timeout = DEFAULT_COMMON_TIMEOUT_MS;

  public HttpEndpoint(String url) throws IOException {
    this.url = new URL(url);
  }

  public InputStream post(Map<String,Object> params) throws IOException {
    return post(encodeParameters(params));
  }

  public InputStream post(String content) throws IOException {
    HttpURLConnection connection = buildConnection("POST");
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
    return send(connection, content);
  }

  private InputStream send(HttpURLConnection connection, String content) throws IOException {
    DataOutputStream out = new DataOutputStream(connection.getOutputStream());
    out.writeBytes(content);
    out.flush();
    out.close();
    return connection.getInputStream();
  }

  private HttpURLConnection buildConnection(String httpMethod) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) this.url.openConnection();
    connection.setUseCaches(false);
    connection.setDoOutput(true);
    connection.setConnectTimeout(timeout);
    connection.setReadTimeout(timeout);
    connection.setRequestMethod(httpMethod);
    return connection;
  }

  private static String encodeParameters(Map<String,Object> params)
      throws UnsupportedEncodingException {
    String content = "";
    for (Object key : params.keySet()) {
      Object value = params.get(key);
      String ukey = URLEncoder.encode((String) key, String.valueOf(Charsets.UTF_8));
      String uvalue = URLEncoder.encode(String.valueOf(value), String.valueOf(Charsets.UTF_8));
      content += ukey + "=" + uvalue + "&";
    }
    return content.substring(0, content.length()-1);
  }

  public void setTimeout(int timeout) {
    this.timeout = timeout;
  }
}
