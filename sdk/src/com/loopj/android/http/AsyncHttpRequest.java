/*
    Android Asynchronous Http Client
    Copyright (c) 2011 James Smith <james@loopj.com>
    http://loopj.com

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
*/

package com.loopj.android.http;

import java.io.IOException;
import java.net.ConnectException;

import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.AbstractHttpClient;
import org.apache.http.protocol.HttpContext;

import android.util.Log;

class AsyncHttpRequest implements Runnable {
    private final AbstractHttpClient client;
    private final HttpContext context;
    private final HttpUriRequest request;
    private final HttpHost target;
    private final HttpRequest httpRequest;
    private final AsyncHttpResponseHandler responseHandler;
    private boolean isBinaryRequest;
    private int executionCount;

    public AsyncHttpRequest(AbstractHttpClient client, HttpContext context, HttpUriRequest request, AsyncHttpResponseHandler responseHandler) {
        this.client = client;
        this.context = context;
        this.request = request;
        this.responseHandler = responseHandler;
        this.target = null;
        this.httpRequest = null;
        if(responseHandler instanceof BinaryHttpResponseHandler) {
            this.isBinaryRequest = true;
        }
    }
    
    public AsyncHttpRequest(AbstractHttpClient client, HttpContext context, HttpHost pTarget, HttpRequest httpRequest, AsyncHttpResponseHandler responseHandler) {
      this.client = client;
      this.context = context;
      this.target = pTarget;
      this.httpRequest = httpRequest;
      this.request = null;
      this.responseHandler = responseHandler;
      if(responseHandler instanceof BinaryHttpResponseHandler) {
          this.isBinaryRequest = true;
      }
    }

    public void run() {
        try {
            if(responseHandler != null){
                responseHandler.sendStartMessage();
            }

            makeRequestWithRetries();

            if(responseHandler != null) {
                responseHandler.sendFinishMessage();
            }
        } catch (IOException e) {
            if(responseHandler != null) {
                responseHandler.sendFinishMessage();
                if(this.isBinaryRequest) {
                    responseHandler.sendFailureMessage(e, (byte[]) null);
                } else {
                    responseHandler.sendFailureMessage(e, (String) null);
                }
            }
        }
    }

    private void makeRequest() throws IOException {
        if(!Thread.currentThread().isInterrupted()) {
            HttpResponse response = null;
            if(null != request){
              response = client.execute(request, context);
            } else {
              Log.d("com.feedhenry.AsyncHttpRequest", "uri = " + target.getSchemeName() + "://" + target.getHostName() + ":" + target.getPort() );
              response = client.execute(target, httpRequest, context);
            }
            if(!Thread.currentThread().isInterrupted()) {
                if(responseHandler != null) {
                    responseHandler.sendResponseMessage(response);
                }
            } else{
                //TODO: should raise InterruptedException? this block is reached whenever the request is cancelled before its response is received
            }
        }
    }

    private void makeRequestWithRetries() throws ConnectException {
        // This is an additional layer of retry logic lifted from droid-fu
        // See: https://github.com/kaeppler/droid-fu/blob/master/src/main/java/com/github/droidfu/http/BetterHttpRequestBase.java
        boolean retry = true;
        IOException cause = null;
        HttpRequestRetryHandler retryHandler = client.getHttpRequestRetryHandler();
        while (retry) {
            try {
                makeRequest();
                return;
            } catch (IOException e) {
                cause = e;
                retry = retryHandler.retryRequest(cause, ++executionCount, context);
            } catch (NullPointerException e) {
                // there's a bug in HttpClient 4.0.x that on some occasions causes
                // DefaultRequestExecutor to throw an NPE, see
                // http://code.google.com/p/android/issues/detail?id=5255
                cause = new IOException("NPE in HttpClient" + e.getMessage());
                retry = retryHandler.retryRequest(cause, ++executionCount, context);
            } catch (Exception ee){
                Log.e("AsyncHttpRequest", ee.getMessage(), ee);
                cause = new IOException(ee.getMessage());
                retry = retryHandler.retryRequest(cause, ++executionCount, context);
            }
        }

        // no retries left, crap out with exception
        ConnectException ex = new ConnectException();
        ex.initCause(cause);
        throw ex;
    }
}