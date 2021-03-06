package com.rapidftr.activity;

import android.os.Bundle;
import android.view.View;
import com.rapidftr.R;
import com.rapidftr.task.AsyncTaskWithDialog;
import org.json.JSONException;

public class RegisterChildActivity extends BaseChildActivity {

    @Override
    protected void initializeLabels() throws JSONException {
        setLabel(R.string.save);
        setTitle(getString(R.string.new_registration));
    }

    protected void initializeView() {
        setContentView(R.layout.activity_register_child);
        findViewById(R.id.submit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                saveChild();
            }
        });
    }

    @Override
    protected void saveChild() {
        AsyncTaskWithDialog.wrap(this, new SaveChildTask(), R.string.save_child_progress, R.string.save_child_success, R.string.save_child_invalid).execute();
    }

    @Override
    public void onBackPressed() {
        if(child.isValid()){
           showAlertDialog();
        }else{
            super.onBackPressed();
        }
    }
}
