package de.handler.mobile.android.videobox;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AppIdentifier;
import com.google.android.gms.nearby.connection.AppMetadata;
import com.google.android.gms.nearby.connection.Connections;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractNearbyActivity extends AbstractActivity implements
		GoogleApiClient.ConnectionCallbacks,
		GoogleApiClient.OnConnectionFailedListener,
		Connections.ConnectionRequestListener,
		Connections.MessageListener,
		Connections.EndpointDiscoveryListener {

	private static final int REQUEST_RESOLVE_ERROR = 1001;
	private static final long TIMEOUT_ADVERTISE = 1000L * 30L;
	private static final long TIMEOUT_DISCOVER = 1000L * 30L;

	protected GoogleApiClient mGoogleApiClient;

	private String mOtherEndpointId;


	protected abstract void onNearbyConnected();


	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		this.initNearbyServices();
	}

	@Override
	protected void onStart() {
		super.onStart();
		mGoogleApiClient.connect();
	}

	@Override
	protected void onStop() {
		super.onStop();
		if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
			mGoogleApiClient.disconnect();
		}
	}


	@Override
	public void onConnected(@Nullable Bundle bundle) {
		onNearbyConnected();
	}

	@Override
	public void onConnectionSuspended(int cause) {
		if (BuildConfig.DEBUG) {
			Log.e(AbstractNearbyActivity.class.getName(), "GoogleApiClient disconnected with cause: " + cause);
		}
	}

	@Override
	public void onConnectionRequest(final String endpointId, String deviceId, String endpointName,
									final byte[] payload) {
		// This device is advertising and has received a connection request. Show a dialog asking
		// the user if they would like to connect and accept or reject the request accordingly.
		AlertDialog alertDialog = new AlertDialog.Builder(this)
				.setTitle(R.string.connection_request_title)
				.setMessage(String.format(getString(R.string.connection_request_content), endpointName))
				.setCancelable(false)
				.setPositiveButton(getString(R.string.button_connect), new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						Nearby.Connections.acceptConnectionRequest(mGoogleApiClient, endpointId,
								payload, AbstractNearbyActivity.this)
								.setResultCallback(new ResultCallback<Status>() {
									@Override
									public void onResult(@NonNull Status status) {
										if (status.isSuccess()) {
											InformationHandler.showInfo(
													R.string.successfully_connected,
													findViewById(android.R.id.content));
										} else {
											if (BuildConfig.DEBUG) {
												Log.e(AbstractNearbyActivity.class.getName(),
														"acceptConnectionRequest: FAILURE");
											}
										}
									}
								});
					}
				})
				.setNegativeButton(getString(R.string.button_abort), new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						Nearby.Connections.rejectConnectionRequest(mGoogleApiClient, endpointId);
					}
				}).create();

		alertDialog.show();
	}

	@Override
	public void onEndpointFound(final String endpointId, String deviceId, String serviceId,
								final String endpointName) {
		this.connectTo(endpointId, endpointName);
	}

	@Override
	public void onEndpointLost(String endpointId) {

	}

	@Override
	public void onMessageReceived(String endpointId, byte[] payload, boolean isReliable) {

	}

	@Override
	public void onDisconnected(String endpointId) {

	}

	@Override
	public void onConnectionFailed(@NonNull ConnectionResult result) {
		if (result.hasResolution()) {
			try {
				result.startResolutionForResult(AbstractNearbyActivity.this, REQUEST_RESOLVE_ERROR);
			} catch (IntentSender.SendIntentException e) {
				if (BuildConfig.DEBUG) {
					Log.e(AbstractNearbyActivity.class.getName(),
							"GoogleApiClient caused an SendIntentException with message: " + e.getMessage());
				}
			}
		} else {
			if (BuildConfig.DEBUG) {
				Log.e(AbstractNearbyActivity.class.getName(), "GoogleApiClient connection failed");
			}
			InformationHandler.showInfo(
					R.string.error_nearby_connection_failed,
					findViewById(android.R.id.content));
		}
	}


	protected void startAdvertising() {
		if (!ConnectivityHelper.isConnectedToNetwork(this)) {
			Log.d(AbstractNearbyActivity.class.getName(), "startAdvertising: not connected to WiFi network.");
			return;
		}

		// Advertising with an AppIdentifer lets other devices on the network discover
		// this application and prompt the user to install the application.
		List<AppIdentifier> appIdentifierList = new ArrayList<>();
		appIdentifierList.add(new AppIdentifier(getPackageName()));
		AppMetadata appMetadata = new AppMetadata(appIdentifierList);

		// Advertise for Nearby Connections. This will broadcast the service id defined in
		// AndroidManifest.xml. By passing 'null' for the name, the Nearby Connections API
		// will construct a default name based on device model such as 'LGE Nexus 5'.
		Nearby.Connections.startAdvertising(
				mGoogleApiClient, null, appMetadata, TIMEOUT_ADVERTISE, this)
				.setResultCallback(new ResultCallback<Connections.StartAdvertisingResult>() {
					@Override
					public void onResult(@NonNull Connections.StartAdvertisingResult result) {
						if (!result.getStatus().isSuccess()) {
							// If the user hits 'Advertise' multiple times in the timeout window,
							// the error will be STATUS_ALREADY_ADVERTISING
							int statusCode = result.getStatus().getStatusCode();
							if (statusCode == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) {
								InformationHandler.showInfo(
										R.string.info_nearby_already_advertising + statusCode,
										findViewById(android.R.id.content));
							} else {
								InformationHandler.showInfo(
										R.string.error_nearby_connection + statusCode,
										findViewById(android.R.id.content));
							}
						}
					}
				});
	}

	protected void stopAdvertising() {
		Nearby.Connections.stopAdvertising(mGoogleApiClient);
	}

	protected void startDiscovery() {
		if (!ConnectivityHelper.isConnectedToNetwork(this)) {
			Log.e(AbstractNearbyActivity.class.getName(), "startDiscovery: not connected to WiFi network.");
			return;
		}

		// Discover nearby apps that are advertising with the required service ID.
		String serviceId = getString(R.string.nearby_service_id);
		Nearby.Connections.startDiscovery(mGoogleApiClient, serviceId, TIMEOUT_DISCOVER, this)
				.setResultCallback(new ResultCallback<Status>() {
					@Override
					public void onResult(@NonNull Status status) {
						if (!status.isSuccess()) {
							// If the user hits 'Discover' multiple times in the timeout window,
							// the error will be STATUS_ALREADY_DISCOVERING
							int statusCode = status.getStatusCode();
							if (statusCode == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) {
								InformationHandler.showInfo(
										R.string.info_nearby_already_discovering + statusCode,
										findViewById(android.R.id.content));
							} else {
								InformationHandler.showInfo(
										R.string.error_nearby_connection + statusCode,
										findViewById(android.R.id.content));
							}
						}
					}
				});
	}

	protected void sendMessage() {
		// Sends a reliable message, which is guaranteed to be delivered eventually and to respect
		// message ordering from sender to receiver. Nearby.Connections.sendUnreliableMessage
		// should be used for high-frequency messages where guaranteed delivery is not required, such
		// as showing one player's cursor location to another. Unreliable messages are often
		// delivered faster than reliable messages.
		String msg = "test";
		Nearby.Connections.sendReliableMessage(mGoogleApiClient, mOtherEndpointId, msg.getBytes());
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == REQUEST_RESOLVE_ERROR) {
			if (resultCode == RESULT_OK) {
				mGoogleApiClient.connect();
			} else {
				if (BuildConfig.DEBUG) {
					Log.e(AbstractNearbyActivity.class.getName(),
							"GoogleApiClient connection failed. Unable to resolve.");
				}
			}
		} else {
			super.onActivityResult(requestCode, resultCode, data);
		}
	}


	private void connectTo(String endpointId, final String endpointName) {
		// Send a connection request to a remote endpoint. By passing 'null' for the name,
		// the Nearby Connections API will construct a default name based on device model
		// such as 'LGE Nexus 5'.
		Nearby.Connections.sendConnectionRequest(mGoogleApiClient, null, endpointId, null,
				new Connections.ConnectionResponseCallback() {
					@Override
					public void onConnectionResponse(String endpointId, Status status,
													 byte[] bytes) {
						if (status.isSuccess()) {
							mOtherEndpointId = endpointId;
						} else if (BuildConfig.DEBUG) {
							Log.e(AbstractNearbyActivity.class.getName(), "onConnectionResponse: " + endpointName + " FAILURE");
						}
					}
				}, this);
	}

	private void initNearbyServices() {
		mGoogleApiClient = new GoogleApiClient.Builder(this)
				.addApi(Nearby.CONNECTIONS_API)
				.addConnectionCallbacks(this)
				.enableAutoManage(this, this)
				.build();
	}
}