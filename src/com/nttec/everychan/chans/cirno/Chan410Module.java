/*
 * Everychan Android (Meta Imageboard Client)
 * Copyright (C) 2014-2016  miku-nyan <https://github.com/miku-nyan>
 *     
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nttec.everychan.chans.cirno;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.HttpHeaders;
import cz.msebera.android.httpclient.NameValuePair;
import cz.msebera.android.httpclient.client.entity.UrlEncodedFormEntity;
import cz.msebera.android.httpclient.cookie.Cookie;
import cz.msebera.android.httpclient.impl.cookie.BasicClientCookie;
import cz.msebera.android.httpclient.message.BasicNameValuePair;

import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.support.v4.content.res.ResourcesCompat;
import com.nttec.everychan.R;
import com.nttec.everychan.api.AbstractChanModule;
import com.nttec.everychan.api.interfaces.CancellableTask;
import com.nttec.everychan.api.interfaces.ProgressListener;
import com.nttec.everychan.api.models.BoardModel;
import com.nttec.everychan.api.models.CaptchaModel;
import com.nttec.everychan.api.models.DeletePostModel;
import com.nttec.everychan.api.models.PostModel;
import com.nttec.everychan.api.models.SendPostModel;
import com.nttec.everychan.api.models.SimpleBoardModel;
import com.nttec.everychan.api.models.ThreadModel;
import com.nttec.everychan.api.models.UrlPageModel;
import com.nttec.everychan.api.util.ChanModels;
import com.nttec.everychan.api.util.WakabaUtils;
import com.nttec.everychan.common.IOUtils;
import com.nttec.everychan.http.ExtendedMultipartBuilder;
import com.nttec.everychan.http.streamer.HttpRequestModel;
import com.nttec.everychan.http.streamer.HttpResponseModel;
import com.nttec.everychan.http.streamer.HttpStreamer;
import com.nttec.everychan.http.streamer.HttpWrongStatusCodeException;
import com.nttec.everychan.lib.org_json.JSONObject;

public class Chan410Module extends AbstractChanModule {
    
    static final String CHAN410_NAME = "410chan.org";
    static final String CHAN410_DOMAIN = "410chan.org";
    static final String CHAN410_URL = "http://" + CHAN410_DOMAIN + "/";
    
    private static final String PREF_KEY_FAPTCHA_COOKIES = "PREF_KEY_FAPTCHA_COOKIES";
    
    public Chan410Module(SharedPreferences preferences, Resources resources) {
        super(preferences, resources);
    }
    
    @Override
    public String getChanName() {
        return CHAN410_NAME;
    }
    
    @Override
    public String getDisplayingName() {
        return "410chan";
    }
    
    @Override
    public Drawable getChanFavicon() {
        return ResourcesCompat.getDrawable(resources, R.drawable.favicon_410chan, null);
    }
    
    @Override
    protected void initHttpClient() {
        JSONObject savedCookies = new JSONObject(preferences.getString(getSharedKey(PREF_KEY_FAPTCHA_COOKIES), "{}"));
        for (String board : Chan410Boards.ALL_BOARDS_SET) {
            String value = savedCookies.optString(board);
            if (value != null && value.length() > 0) {
                BasicClientCookie c = new BasicClientCookie(board, value);
                c.setDomain("." + CHAN410_DOMAIN);
                c.setPath("/");
                httpClient.getCookieStore().addCookie(c);
            }
        }
    }
    
    private void saveFaptchaCookies() {
        JSONObject savedCookies = new JSONObject();
        List<Cookie> cookies = httpClient.getCookieStore().getCookies();
        for (Cookie cookie : cookies) {
            if (cookie.getName().length() <= 3 && Chan410Boards.ALL_BOARDS_SET.contains(cookie.getName())) {
                savedCookies.put(cookie.getName(), cookie.getValue());
            }
        }
        preferences.edit().putString(getSharedKey(PREF_KEY_FAPTCHA_COOKIES), savedCookies.toString()).commit();
    }
    
    @Override
    public SimpleBoardModel[] getBoardsList(ProgressListener listener, CancellableTask task, SimpleBoardModel[] oldBoardsList) throws Exception {
        return Chan410Boards.getBoardsList();
    }
    
    @Override
    public BoardModel getBoard(String shortName, ProgressListener listener, CancellableTask task) throws Exception {
        return Chan410Boards.getBoard(shortName);
    }
    
    private ThreadModel[] readWakabaPage(String url, ProgressListener listener, CancellableTask task, boolean checkIfModified, boolean isInt)
            throws Exception {
        HttpResponseModel responseModel = null;
        Chan410Reader in = null;
        HttpRequestModel rqModel = HttpRequestModel.builder().setGET().setCheckIfModified(checkIfModified).build();
        try {
            responseModel = HttpStreamer.getInstance().getFromUrl(url, rqModel, httpClient, listener, task);
            if (responseModel.statusCode == 200) {
                in = isInt ? new Chan410IntReader(responseModel.stream) : new Chan410Reader(responseModel.stream);
                if (task != null && task.isCancelled()) throw new Exception("interrupted");
                return in.readWakabaPage();
            } else {
                if (responseModel.notModified()) return null;
                throw new HttpWrongStatusCodeException(responseModel.statusCode, responseModel.statusCode + " - " + responseModel.statusReason);
            }
        } catch (Exception e) {
            if (responseModel != null) HttpStreamer.getInstance().removeFromModifiedMap(url);
            throw e;
        } finally {
            IOUtils.closeQuietly(in);
            if (responseModel != null) responseModel.release();
        }
    }
    
    @Override
    public ThreadModel[] getThreadsList(String boardName, int page, ProgressListener listener, CancellableTask task, ThreadModel[] oldList)
            throws Exception {
        UrlPageModel urlModel = new UrlPageModel();
        urlModel.chanName = CHAN410_NAME;
        urlModel.type = UrlPageModel.TYPE_BOARDPAGE;
        urlModel.boardName = boardName;
        urlModel.boardPage = page;
        String url = buildUrl(urlModel);
        
        ThreadModel[] threads = readWakabaPage(url, listener, task, oldList != null, boardName.equals("int"));
        if (threads == null) {
            return oldList;
        } else {
            return threads;
        }
    }
    
    @Override
    public PostModel[] getPostsList(String boardName, String threadNumber, ProgressListener listener, CancellableTask task, PostModel[] oldList)
            throws Exception {
        UrlPageModel urlModel = new UrlPageModel();
        urlModel.chanName = CHAN410_NAME;
        urlModel.type = UrlPageModel.TYPE_THREADPAGE;
        urlModel.boardName = boardName;
        urlModel.threadNumber = threadNumber;
        String url = buildUrl(urlModel);
        
        ThreadModel[] threads = readWakabaPage(url, listener, task, oldList != null, boardName.equals("int"));
        if (threads == null) {
            return oldList;
        } else {
            if (threads.length == 0) throw new Exception("Unable to parse response");
            return oldList == null ? threads[0].posts : ChanModels.mergePostsLists(Arrays.asList(oldList), Arrays.asList(threads[0].posts));
        }
    }
    
    @Override
    public CaptchaModel getNewCaptcha(String boardName, String threadNumber, ProgressListener listener, CancellableTask task) throws Exception {
        String checkUrl = CHAN410_URL + "api_adaptive.php?board=" + boardName;
        if (HttpStreamer.getInstance().getStringFromUrl(checkUrl, HttpRequestModel.DEFAULT_GET, httpClient, listener, task, false).trim().
                equals("1")) return null;
        String captchaUrl = CHAN410_URL + "faptcha.php?board=" + boardName;
        return downloadCaptcha(captchaUrl, listener, task);
    }
    
    @Override
    public String sendPost(SendPostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        String url = CHAN410_URL + "board.php";
        ExtendedMultipartBuilder postEntityBuilder = ExtendedMultipartBuilder.create().setDelegates(listener, task).
                addString("board", model.boardName).
                addString("replythread", model.threadNumber != null ? model.threadNumber : "0").
                addString("name", model.name).
                addString("faptcha", model.captchaAnswer).
                addString("subject", model.subject).
                addString("message", model.comment).
                addString("postpassword", model.password);
        if (model.sage) postEntityBuilder.addString("sage", "on");
        postEntityBuilder.addString("noko", "on");
        if (model.attachments != null && model.attachments.length > 0)
            postEntityBuilder.addFile("imagefile", model.attachments[0], model.randomHash);
        
        HttpRequestModel request = HttpRequestModel.builder().setPOST(postEntityBuilder.build()).setNoRedirect(true).build();
        HttpResponseModel response = null;
        try {
            response = HttpStreamer.getInstance().getFromUrl(url, request, httpClient, null, task);
            if (response.statusCode == 302) {
                for (Header header : response.headers) {
                    if (header != null && HttpHeaders.LOCATION.equalsIgnoreCase(header.getName())) {
                        if (header.getValue().trim().length() == 0) throw new Exception();
                        return fixRelativeUrl(header.getValue());
                    }
                }
            } else if (response.statusCode == 200) {
                ByteArrayOutputStream output = new ByteArrayOutputStream(1024);
                IOUtils.copyStream(response.stream, output);
                String htmlResponse = output.toString("UTF-8");
                if (!htmlResponse.contains("<blockquote")) {
                    int start = htmlResponse.indexOf("<h2 style=\"font-size: 2em;font-weight: bold;text-align: center;\">");
                    if (start != -1) {
                        int end = htmlResponse.indexOf("</h2>", start + 65);
                        if (end != -1) {
                            throw new Exception(htmlResponse.substring(start + 65, end).trim());
                        }
                    }
                }
            } else throw new Exception(response.statusCode + " - " + response.statusReason);
        } finally {
            if (response != null) response.release();
            saveFaptchaCookies();
        }
        
        return null;
    }
    
    @Override
    public String deletePost(DeletePostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        String url = CHAN410_URL + "board.php";
        
        List<NameValuePair> pairs = new ArrayList<NameValuePair>();
        pairs.add(new BasicNameValuePair("board", model.boardName));
        pairs.add(new BasicNameValuePair("delete[]", model.postNumber));
        if (model.onlyFiles) pairs.add(new BasicNameValuePair("fileonly", "on"));
        pairs.add(new BasicNameValuePair("postpassword", model.password));
        pairs.add(new BasicNameValuePair("deletepost", "Удалить"));
        
        HttpRequestModel request = HttpRequestModel.builder().setPOST(new UrlEncodedFormEntity(pairs, "UTF-8")).setNoRedirect(true).build();
        String result = HttpStreamer.getInstance().getStringFromUrl(url, request, httpClient, listener, task, false);
        if (result.contains("Неверный пароль")) throw new Exception("Неверный пароль");
        return null;
    }
    
    @Override
    public String reportPost(DeletePostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        String url = CHAN410_URL + "board.php";
        
        List<NameValuePair> pairs = new ArrayList<NameValuePair>();
        pairs.add(new BasicNameValuePair("board", model.boardName));
        pairs.add(new BasicNameValuePair("delete[]", model.postNumber));
        pairs.add(new BasicNameValuePair("reportpost", "Пожаловаться"));
        
        HttpRequestModel request = HttpRequestModel.builder().setPOST(new UrlEncodedFormEntity(pairs, "UTF-8")).setNoRedirect(true).build();
        String result = HttpStreamer.getInstance().getStringFromUrl(url, request, httpClient, listener, task, false);
        if (result.contains("Post successfully reported")) return null;
        throw new Exception(result);
    }
    
    @Override
    public String buildUrl(UrlPageModel model) throws IllegalArgumentException {
        if (!model.chanName.equals(CHAN410_NAME)) throw new IllegalArgumentException("wrong chan");
        return WakabaUtils.buildUrl(model, CHAN410_URL);
    }
    
    @Override
    public UrlPageModel parseUrl(String url) throws IllegalArgumentException {
        return WakabaUtils.parseUrl(url, CHAN410_NAME, CHAN410_DOMAIN);
    }
    
}
