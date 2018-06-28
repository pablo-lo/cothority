package ch.epfl.dedis.cothority.demo.cothoritydemo;

import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;

import java.util.Arrays;

import ch.epfl.dedis.lib.eventlog.Event;
import ch.epfl.dedis.lib.exception.CothorityException;
import ch.epfl.dedis.lib.omniledger.InstanceId;
import ch.epfl.dedis.lib.omniledger.OmniledgerRPC;
import ch.epfl.dedis.lib.omniledger.contracts.EventLogInstance;
import ch.epfl.dedis.lib.omniledger.darc.Signer;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                Signer admin = SecureKG.getSigner();
                OmniledgerRPC ol = null;
                try {
                    ol = SecureKG.getOmniledgerRPC();
                    EventLogInstance el = new EventLogInstance(ol, SecureKG.getEventlogId());
                    InstanceId key = el.log(new Event("android-hello", "Hello from MainActivity!"), Arrays.asList(admin));
                } catch (CothorityException e) {
                    e.printStackTrace();
                }

                Snackbar.make(view, "A test event was sent to the event log.", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        return super.onOptionsItemSelected(item);
    }
}
