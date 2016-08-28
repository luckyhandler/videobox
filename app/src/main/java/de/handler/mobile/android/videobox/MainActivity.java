package de.handler.mobile.android.videobox;

import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.NavigationView;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import static de.handler.mobile.android.videobox.PermissionRequestCode.RequestCodes.REQUEST_CODE_PERMISSION_CAMERA;

public class MainActivity extends AbstractNearbyActivity
		implements NavigationView.OnNavigationItemSelectedListener {

	private static final int FRAGMENT_CONTAINER = R.id.main_container;
	private static final String CAMERA_FRAGMENT = "camera_fragment" + MainActivity.class.getCanonicalName();
	private static final String REMOTE_FRAGMENT = "remote_fragment" + MainActivity.class.getCanonicalName();
	public static final int REQUEST_CODE_CAMERA_APP = 401;
	private DrawerLayout mDrawer;

	@Override
	protected void setRootView() {
		mRootView = findViewById(R.id.fab);
		mRootViewId = FRAGMENT_CONTAINER;
	}

	@Override
	protected void onPermissionGranted(int requestCodePermission, boolean granted) {
		if (requestCodePermission == REQUEST_CODE_PERMISSION_CAMERA) {
			if (!granted) {
				showInfo(R.string.error_no_camera_permission);
				return;
			}

			Fragment fragment =
					getSupportFragmentManager().findFragmentByTag(CAMERA_FRAGMENT);
			if (fragment instanceof CameraFragment) {
				((CameraFragment) fragment).onPermissionGranted();
			}
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		Toolbar toolbar = setToolbar(R.id.toolbar);

		View button = findViewById(R.id.fab);
		button.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				Snackbar.make(view, getString(R.string.snackbar_fab_action_title), Snackbar.LENGTH_LONG)
						.setAction(getString(R.string.snackbar_fab_action), new View.OnClickListener() {
							@Override
							public void onClick(View view) {
								startDiscovery();
							}
						}).show();
			}
		});

		mDrawer = (DrawerLayout) findViewById(R.id.drawer_layout);
		ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
				this, mDrawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
		mDrawer.addDrawerListener(toggle);
		toggle.syncState();

		NavigationView navigationView = (NavigationView) findViewById(R.id.nav_view);
		navigationView.setNavigationItemSelectedListener(this);

		replaceFragment(getSupportFragmentManager(), new WelcomeFragment(), FRAGMENT_CONTAINER, null);
	}

	@Override
	public void onBackPressed() {
		if (mDrawer.isDrawerOpen(GravityCompat.START)) {
			mDrawer.closeDrawer(GravityCompat.START);
		} else {
			super.onBackPressed();
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();

		//noinspection SimplifiableIfStatement
		if (id == R.id.action_settings) {
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onNearbyConnected() {
		startAdvertising();
	}

	@Override
	protected void showCamera() {
		replaceFragment(getSupportFragmentManager(), new CameraFragment(), R.id.main_container, CAMERA_FRAGMENT);
	}

	@Override
	protected void showRemote() {
		replaceFragment(getSupportFragmentManager(), new RemoteFragment(), R.id.main_container, REMOTE_FRAGMENT);
	}

	@Override
	public boolean onNavigationItemSelected(@NonNull MenuItem item) {
		// Handle navigation view item clicks here.
		int id = item.getItemId();

		if (id == R.id.nav_camera) {
			// Handle the camera action
		} else if (id == R.id.nav_gallery) {

		} else if (id == R.id.nav_manage) {

		} else if (id == R.id.nav_share) {

		} else if (id == R.id.nav_send) {

		}

		mDrawer.closeDrawer(GravityCompat.START);
		return true;
	}
}
