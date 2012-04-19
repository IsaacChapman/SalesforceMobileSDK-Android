/*
 * Copyright (c) 2011, salesforce.com, inc.
 * All rights reserved.
 * Redistribution and use of this software in source and binary forms, with or
 * without modification, are permitted provided that the following conditions
 * are met:
 * - Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * - Neither the name of salesforce.com, inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission of salesforce.com, inc.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.salesforce.androidsdk.ui;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

import android.accounts.Account;
import android.accounts.AccountAuthenticatorActivity;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.salesforce.androidsdk.app.ForceApp;
import com.salesforce.androidsdk.auth.HttpAccess;
import com.salesforce.androidsdk.auth.OAuth2;
import com.salesforce.androidsdk.auth.OAuth2.IdServiceResponse;
import com.salesforce.androidsdk.auth.OAuth2.TokenEndpointResponse;
import com.salesforce.androidsdk.rest.ClientManager;
import com.salesforce.androidsdk.rest.ClientManager.LoginOptions;
import com.salesforce.androidsdk.security.PasscodeManager;

/**
 * Login Activity: takes care of authenticating the user.
 * Authorization happens inside a web view. Once we get our authorization code,
 * we swap it for an access and refresh token a create an account through the
 * account manager to store them.
 */
public class LoginActivity extends AccountAuthenticatorActivity {

	// Key for login servers properties stored in preferences
	public static final String SERVER_URL_PREFS_SETTINGS = "server_url_prefs";
	public static final String SERVER_URL_PREFS_CUSTOM_LABEL = "server_url_custom_label";
	public static final String SERVER_URL_PREFS_CUSTOM_URL = "server_url_custom_url";
	public static final String SERVER_URL_PREFS_WHICH_SERVER = "which_server_index";
	public static final String SERVER_URL_CURRENT_SELECTION = "server_url_current_string";
	
	// Request code when calling server picker activity
    public static final int PICK_SERVER_CODE = 10;	
	
    private SalesforceR salesforceR;
	private boolean wasBackgrounded;
	private boolean completedAuthFlow;
	private WebView webView;
	private LoginOptions loginOptions;
	private AccountOptions accountOptions;

    /**************************************************************************************************
     *
     * Activity lifecycle
     * 
     **************************************************************************************************/
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Object which allows reference to resources living outside the SDK
		salesforceR = ForceApp.APP.getSalesforceR();
		
		// Getting login options from intent's extras
		loginOptions = LoginOptions.fromBundle(getIntent().getExtras());
		
		// We'll show progress in the window title bar
		getWindow().requestFeature(Window.FEATURE_PROGRESS);
		getWindow().requestFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
		
		// Setup content view
		setContentView(salesforceR.layoutLogin());

		// Setup the WebView.
		webView = (WebView) findViewById(salesforceR.idLoginWebView());
		webView.getSettings().setJavaScriptEnabled(true);
		webView.setWebViewClient(new AuthWebViewClient());
		webView.setWebChromeClient(new AuthWebChromeClient());

		// Restore webview's state if available.
		// This ensures the user is not forced to type in credentials again
		// once the auth process has been kicked off.
		if (savedInstanceState != null) {
			webView.restoreState(savedInstanceState);
		}
		// Otherwise start clean
		else {
			clearCookies();
		}
		loadLoginPage();
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (wasBackgrounded) {
			webView.clearView();
			loadLoginPage();
			wasBackgrounded = false;
		}
		else if (completedAuthFlow) {
			loginOptions.passcodeHash = ForceApp.APP.getPasscodeHash();
			addAccount();
			finish(); // truly done now
			completedAuthFlow = false;
		}
	}

	@Override
	public void onSaveInstanceState(Bundle bundle) {
		super.onSaveInstanceState(bundle);
		webView.saveState(bundle);
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			wasBackgrounded = true;
			moveTaskToBack(true);
			return true;
		}
		return super.onKeyDown(keyCode, event);
	}

    /**************************************************************************************************
     *
     * Authentication flow 
     * 
     **************************************************************************************************/
	
	/**
	 * Tells the webview to load the authorization page. 
	 * We also update the window title, so its easier to 
	 * see which system you're logging in to
	 */
	protected void loadLoginPage() {
		// Filling in loginUrl
		loginOptions.loginUrl = getLoginUrl();

		try {
			URI uri = OAuth2.getAuthorizationUrl(
			        new URI(loginOptions.loginUrl), 
			        loginOptions.oauthClientId, 
			        loginOptions.oauthCallbackUrl,
			        loginOptions.oauthScopes);
			setTitle(loginOptions.loginUrl);
			webView.loadUrl(uri.toString());
		} catch (URISyntaxException ex) {
			showError(ex);
		}
	}

	/**
	 * Called when the user facing part of the auth flow completed successfully.
	 * The last step is to call the identity service to get the username.
	 */
	protected void onAuthFlowComplete(TokenEndpointResponse tr) {
		
		IdServiceResponse id = null;
		Exception exception = null;
		
		// Running identityTask to call identity service
		try {
			IdentityTask identityTask = new IdentityTask();
			Pair<IdServiceResponse, Exception> idExec = identityTask.execute(tr).get();
			id = idExec.first;
			exception = idExec.second;
		} catch(Exception e) {
			exception = e;
		}

		// Identity service call failed
		if (exception != null) {
			Log.w("LoginActiviy.onAuthFlowComplete", exception);
			onAuthFlowError(getString(salesforceR.stringGenericAuthenticationErrorTitle()),
					getString(salesforceR.stringGenericAuthenticationErrorBody()));
			finish();

		}
		// Identity service succeeded
		else {
			// Putting together all the information needed to create the new account
			accountOptions = new AccountOptions(id.username, tr.refreshToken, tr.authToken, tr.instanceUrl, tr.orgId, tr.userId);
			
			// Screen lock required by mobile policy
			if (id.screenLockTimeout > 0) {
				PasscodeManager passcodeManager = ForceApp.APP.getPasscodeManager();
				passcodeManager.setTimeoutMs(id.screenLockTimeout * 1000 * 60 /* converting minutes to milliseconds*/);
				passcodeManager.setMinPasscodeLength(id.pinLength);
				
				completedAuthFlow = true;
				// This will bring up the create passcode screen - we will create the account in onResume
				ForceApp.APP.getPasscodeManager().setEnabled(true);
				ForceApp.APP.getPasscodeManager().lockIfNeeded(this, true);
			}
			// No screen lock required or no mobile policy specified
			else {
				addAccount();
				finish();
			}
		}
		
	}

	/**
	 * Called when the user facing part of the auth flow completed with an error.
	 * We show the user an error and end the activity.
	 */
	protected void onAuthFlowError(String error, String errorDesc) {
		Log.w("AbstractLoginActivity:onAuthFlowError", error + ":" + errorDesc);

		// look for deny. kick them back to login, so clear cookies and repoint browser
		if ("access_denied".equals(error)
				&& "end-user denied authorization".equals(errorDesc)) {

			webView.post(new Runnable() {
				@Override
				public void run() {
					clearCookies();
					loadLoginPage();
				}
			});

		} else {

			Toast t = Toast.makeText(this, error + " : " + errorDesc,
					Toast.LENGTH_LONG);

			webView.postDelayed(new Runnable() {
				@Override
				public void run() {
					finish();
				}
			}, t.getDuration());
			t.show();

		}
	}
	
	protected void addAccount() {

		ClientManager clientManager = new ClientManager(this, ForceApp.APP.getAccountType(), loginOptions);
		
		// Old account
		Account[] oldAccounts = clientManager.getAccounts();
		
		// Create account name (shown in Settings -> Accounts & sync)
		String accountName = buildAccountName(accountOptions.username);

		// New account
		Bundle extras = clientManager.createNewAccount(accountName,
				accountOptions.username, 
				accountOptions.refreshToken,
				accountOptions.authToken, 
				accountOptions.instanceUrl,
				loginOptions.loginUrl, 
				loginOptions.oauthClientId,
				accountOptions.orgId, 
				accountOptions.userId,
				loginOptions.passcodeHash);
		
	setAccountAuthenticatorResult(extras);

		// Remove old accounts
		clientManager.removeAccounts(oldAccounts);
	}

	/**
	 * Override this method to customize the login url.
	 * @return login url
	 */
	protected String getLoginUrl() {
		SharedPreferences settings = getSharedPreferences(
				SERVER_URL_PREFS_SETTINGS, Context.MODE_PRIVATE);

		return settings.getString(SERVER_URL_CURRENT_SELECTION, OAuth2.DEFAULT_LOGIN_URL);
	}    
	
	
    /**************************************************************************************************
     *
     * Misc
     * 
     **************************************************************************************************/
	
	protected void showError(Exception exception) {
		Toast.makeText(this,
				getString(salesforceR.stringGenericError(), exception.toString()),
				Toast.LENGTH_LONG).show();
	}

    /**
     * Return name to be shown for account in Settings -> Accounts & Sync
     * @param username
     * @return
     */
    protected String buildAccountName(String username) {
    	return String.format("%s (%s)", username, ForceApp.APP.getApplicationName());
    }

    
    /**************************************************************************************************
     *
     * Buttons click handlers
     * 
     **************************************************************************************************/

	/**
	 * Called when "Clear cookies" button is clicked.
	 * Clear cookies and reload login page.
	 * @param v
	 */
	public void onClearCookiesClick(View v) {
		clearCookies();
		loadLoginPage();
	}

	protected void clearCookies() {
		CookieManager cm = CookieManager.getInstance();
		cm.removeAllCookie();
	}
	
	/**
	 * Called when "Pick server" button is clicked.
	 * Start ServerPickerActivity
	 * @param v
	 */
	public void onPickServerClick(View v) {
		Intent i = new Intent(this, ServerPickerActivity.class);
	    startActivityForResult(i, PICK_SERVER_CODE);
	}
	
	/*
	 * Called when ServerPickerActivity completes.
	 * Reload login page.
	 */
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == PICK_SERVER_CODE && resultCode == Activity.RESULT_OK) {
            loadLoginPage();
		}
		else {
	        super.onActivityResult(requestCode, resultCode, data);
	    }
	}
	
	/**************************************************************************************************
	 * 
	 * Helper inner classes
	 * 
	 **************************************************************************************************/

	/**
	 * Background task that takes care of calling the identity service to get the user's username
	 * and the mobile policy (if available)
	 */
	protected class IdentityTask extends AsyncTask<TokenEndpointResponse, Boolean, Pair<IdServiceResponse, Exception>> {

		@Override
		protected final Pair<IdServiceResponse, Exception> doInBackground(
				TokenEndpointResponse... params) {
			try {
				publishProgress(true);

				TokenEndpointResponse tr= params[0];
				IdServiceResponse id = OAuth2.callIdentityService(
					HttpAccess.DEFAULT, tr.idUrlWithInstance, tr.authToken);
				return new Pair<IdServiceResponse, Exception>(id, null);
			} catch (Exception e) {
				Log.w("IdentityTask.doInBackground", e);
				return new Pair<IdServiceResponse, Exception>(null, e);
			}
		}

		@Override
		protected void onProgressUpdate(Boolean... values) {
			setProgressBarIndeterminateVisibility(values[0]);
			setProgressBarIndeterminate(values[0]);
		}
	}
	/**
	 * WebChromeClient used to report back progress.
	 *
	 */
	class AuthWebChromeClient extends WebChromeClient {

		@Override
		public void onProgressChanged(WebView view, int newProgress) {
			setProgress(newProgress * 100);
		}
	}

	/**
	 * WebViewClient which intercepts the redirect to the oauth callback url.
	 * That redirect marks the end of the user facing portion of the authentication flow.
	 *
	 */
	class AuthWebViewClient extends WebViewClient {
		@Override
		public boolean shouldOverrideUrlLoading(WebView view, String url) {
			boolean isDone = url.startsWith(loginOptions.oauthCallbackUrl);
			
			if (isDone) {
				Uri callbackUri = Uri.parse(url);
				Map<String, String> params = UriFragmentParser.parse(callbackUri);
				
				String error = params.get("error");
				// Did we fail?
				if (error != null) {
					String errorDesc = params.get("error_description");
					onAuthFlowError(error, errorDesc);
				}
				// Or succeed?
				else {
					TokenEndpointResponse tr = new TokenEndpointResponse(params);
					onAuthFlowComplete(tr);
				}
			}

			return isDone;
		}
	}

	/**
	 * This parses a Uri fragment that uses a queryString style foo=bar&bar=foo
	 * parameter passing (e.g. OAuth2)
	 */
	static class UriFragmentParser {

		/**
		 * look for # error fragments and standard url param errors, like the
		 * user clicked deny on the auth page
		 * 
		 * @param uri
		 * @return
		 */
		public static Map<String, String> parse(Uri uri) {
			Map<String, String> retval = parse(uri.getEncodedFragment());
			if (retval.size() == 0) {
				retval = parse(uri.getEncodedQuery());
			}
			return retval;
		}

		public static Map<String, String> parse(String fragmentString) {
			Map<String, String> res = new HashMap<String, String>();
			if (fragmentString == null)
				return res;
			fragmentString = fragmentString.trim();
			if (fragmentString.length() == 0)
				return res;
			String[] params = fragmentString.split("&");
			for (String param : params) {
				String[] parts = param.split("=");
				res.put(URLDecoder.decode(parts[0]),
						parts.length > 1 ? URLDecoder.decode(parts[1]) : "");
			}
			return res;
		}

		private UriFragmentParser() {
			assert false : "don't construct me!";
		}
	}
	
	/**
	 * Class encapsulating the parameters required to create a new account
	 *
	 */
	public static class AccountOptions {
		public final String username;
		public final String refreshToken;
		public final String authToken;
		public final String instanceUrl;
		public final String orgId;
		public final String userId;
		
		public AccountOptions(String username, String refreshToken,
				String authToken, String instanceUrl, String orgId,
				String userId) {
			super();
			this.username = username;
			this.refreshToken = refreshToken;
			this.authToken = authToken;
			this.instanceUrl = instanceUrl;
			this.orgId = orgId;
			this.userId = userId;
		}
	}
}
