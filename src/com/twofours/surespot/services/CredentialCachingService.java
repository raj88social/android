package com.twofours.surespot.services;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import ch.boye.httpclientandroidlib.cookie.Cookie;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.twofours.surespot.IdentityController;
import com.twofours.surespot.SurespotApplication;
import com.twofours.surespot.SurespotPublicIdentity;
import com.twofours.surespot.common.SurespotConstants;
import com.twofours.surespot.common.SurespotLog;
import com.twofours.surespot.encryption.EncryptionController;

public class CredentialCachingService extends Service {
	private static final String TAG = "CredentialCachingService";

	private final IBinder mBinder = new CredentialCachingBinder();

	private Map<String, String> mPasswords = new HashMap<String, String>();
	private Map<String, Cookie> mCookies = new HashMap<String, Cookie>();
	private static String mLoggedInUser;
	private LoadingCache<String, SurespotPublicIdentity> mPublicIdentities;
	private LoadingCache<String, LoadingCache<String, byte[]>> mSharedSecrets;

	@Override
	public void onCreate() {
		SurespotLog.v(TAG, "onCreate");
		// TODO make display optional?
		Notification notification = new Notification(0, null, System.currentTimeMillis());
		notification.flags |= Notification.FLAG_NO_CLEAR;

		// Intent notificationIntent = new Intent(this, StartupActivity.class);
		// PendingIntent pendingIntent = PendingIntent.getActivity(this, SurespotConstants.IntentRequestCodes.FOREGROUND_NOTIFICATION,
		// notificationIntent, 0);
		// notification.setLatestEventInfo(this, "surespot", "caching credentials", pendingIntent);
		startForeground(SurespotConstants.IntentRequestCodes.FOREGROUND_NOTIFICATION, notification);

		CacheLoader<String, SurespotPublicIdentity> keyCacheLoader = new CacheLoader<String, SurespotPublicIdentity>() {
			@Override
			public SurespotPublicIdentity load(String username) throws Exception {
				String result = SurespotApplication.getNetworkController().getPublicIdentitySync(username);
				if (result != null) {
					return IdentityController.recreatePublicIdentity(result);
				}
				return null;
			}
		};

		CacheLoader<String, LoadingCache<String, byte[]>> secretCacheCacheLoader = new CacheLoader<String, LoadingCache<String, byte[]>>() {

			@Override
			public LoadingCache<String, byte[]> load(String username) throws Exception {
				CacheLoader<String, byte[]> secretCacheLoader = new CacheLoader<String, byte[]>() {

					@Override
					public byte[] load(String username) throws Exception {

						return EncryptionController.generateSharedSecretSync(username);
					}
				};

				return CacheBuilder.newBuilder().build(secretCacheLoader);

			}
		};

		mPublicIdentities = CacheBuilder.newBuilder().build(keyCacheLoader);
		mSharedSecrets = CacheBuilder.newBuilder().build(secretCacheCacheLoader);
	}

	public synchronized void login(String username, String password, Cookie cookie) {
		SurespotLog.v(TAG, "Logging in: " + username);
		mLoggedInUser = username;
		this.mPasswords.put(username, password);
		this.mCookies.put(username, cookie);
	}

	public String getLoggedInUser() {
		return mLoggedInUser;
	}

	public String getPassword(String username) {
		return mPasswords.get(username);
	}

	public Cookie getCookie(String username) {
		return mCookies.get(username);
	}

	public byte[] getSharedSecret(String username) {
		// get the cache for this user
		try {
			LoadingCache<String, byte[]> loadingCache = mSharedSecrets.get(getLoggedInUser());
			return loadingCache.get(username);
		}
		catch (ExecutionException e) {
			SurespotLog.w(TAG, "getSharedSecret: " + e.getMessage());
			return null;
		}

	}

	public synchronized void logout() {
		if (mLoggedInUser != null) {
			SurespotLog.v(TAG, "Logging out: " + mLoggedInUser);
			mPasswords.remove(mLoggedInUser);
			mCookies.remove(mLoggedInUser);
			mLoggedInUser = null;
		}
	}

	public class CredentialCachingBinder extends Binder {
		public CredentialCachingService getService() {
			return CredentialCachingService.this;
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_STICKY;

	}

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;

	}

	// /should probably be done on a thread considering all the work it has to do to load the key the first time
	public SurespotPublicIdentity getSurespotPublicIdentity(String username) {
		try {
			return mPublicIdentities.get(username);
		}
		catch (ExecutionException e) {
			SurespotLog.w(TAG, "getPublicKey", e);
			return null;
		}
	}

	@Override
	public void onDestroy() {
		SurespotLog.v(TAG, "onDestroy");
	}

}
