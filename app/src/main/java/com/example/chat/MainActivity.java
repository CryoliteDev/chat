package com.example.chat;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.firebase.ui.auth.AuthUI;
import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigValue;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    public static final String ANONYMOUS = "anonymous";
    public static final int DEFAULT_MSG_LENGTH_LIMIT = 1000;
    public static final String CHAT_MSG_LENGTH_KEY = "chat_msg_length";
    public static final int RC_SIGN_IN = 1;
    public static final int RC_PHOTO_PICKER = 2;

    private ListView messageListView;
    private MessageAdapter messageAdapter;
    private ProgressBar progressBar;
    private ImageButton photoPickerButton;
    private EditText messageEditText;
    private Button sendButton;

    private String userName;

    private FirebaseDatabase firebaseDatabase;
    private DatabaseReference messagesDatabaseReference;
    private ChildEventListener childEventListener;
    private FirebaseAuth firebaseAuth;
    private FirebaseAuth.AuthStateListener authStateListener;
    private FirebaseStorage firebaseStorage;
    private StorageReference chatPhotosStorageReference;
    private FirebaseRemoteConfig firebaseRemoteConfig;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        userName = ANONYMOUS;

        // Initialize Firebase Components
        firebaseDatabase = FirebaseDatabase.getInstance();
        firebaseAuth = FirebaseAuth.getInstance();
        firebaseStorage = FirebaseStorage.getInstance();
        firebaseRemoteConfig = FirebaseRemoteConfig.getInstance();

        messagesDatabaseReference = firebaseDatabase.getReference().child("messages");
        chatPhotosStorageReference = firebaseStorage.getReference().child("photos");

        // Initialize references to the Views
        progressBar = (ProgressBar) findViewById(R.id.progressBor);
        messageListView = (ListView) findViewById(R.id.messageListView);
        photoPickerButton = (ImageButton) findViewById(R.id.photoPickerButton);
        messageEditText = (EditText) findViewById(R.id.messageEditText);
        sendButton = (Button) findViewById(R.id.sendButton);

        
        // Initialize message ListView and the Adapter
        List<Message> messages = new ArrayList<>();
        messageAdapter = new MessageAdapter(this, R.layout.item_message, messages);
        messageListView.setAdapter(messageAdapter);

        // Initialize progress bar
        progressBar.setVisibility(ProgressBar.INVISIBLE);

        // ImagePicker Btn shows an image picker to send
        photoPickerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/jpeg");
                intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
                startActivityForResult(Intent.createChooser(intent,"Complete action"), RC_PHOTO_PICKER);
            }
        });

        // Enable Send Btn when there's txt in the field
        messageEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                if (charSequence.toString().trim().length() > 0) {
                    sendButton.setEnabled(true);
                } else {
                    sendButton.setEnabled(false);
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });
        messageEditText.setFilters(new InputFilter[] {
                new InputFilter.LengthFilter(DEFAULT_MSG_LENGTH_LIMIT)
        });

        // Sent Btn sends the msg and clears the Text field
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Message message = new Message(messageEditText.getText().toString(), userName, null);
                messagesDatabaseReference.push().setValue(message);

                messageEditText.setText("");
            }
        });

        authStateListener = new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                FirebaseUser user = firebaseAuth.getCurrentUser();

                // Checks if the user is logged in or not
                if (user != null) {
                    onSignedInInitialize(user.getDisplayName());
                } else {
                    onSignedOutCleanup();
                    startActivityForResult(
                            AuthUI.getInstance()
                                .createSignInIntentBuilder()
                                    .setIsSmartLockEnabled(false)
                                .setAvailableProviders(Arrays.asList(
                                        new AuthUI.IdpConfig.GoogleBuilder().build(),
                                        new AuthUI.IdpConfig.EmailBuilder().build()
                                )).build(), RC_SIGN_IN
                    );
                }
            }
        };

        FirebaseRemoteConfigSettings configSettings = new FirebaseRemoteConfigSettings.Builder()
                .setDeveloperModeEnabled(BuildConfig.DEBUG)
                .build();
        firebaseRemoteConfig.setConfigSettings(configSettings);

        Map<String, Object> defaultConfigMap = new HashMap<>();
        defaultConfigMap.put(CHAT_MSG_LENGTH_KEY, DEFAULT_MSG_LENGTH_LIMIT);
        firebaseRemoteConfig.setDefaults(defaultConfigMap);
        fetchConfig();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (requestCode == RC_SIGN_IN) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Signed In", Toast.LENGTH_SHORT).show();
            } else if (resultCode == RESULT_CANCELED) {
                Toast.makeText(this,"Sign In Canceled", Toast.LENGTH_SHORT).show();
                finish();
            }
        } else if (requestCode == RC_PHOTO_PICKER && resultCode == RESULT_OK) {
            Uri selectedImageUri = data.getData();
            //Gets the reference to store the file
            final StorageReference photoRef = chatPhotosStorageReference.child(selectedImageUri.getLastPathSegment());

            photoRef.putFile(selectedImageUri).continueWithTask(
                    new Continuation<UploadTask.TaskSnapshot, Task<Uri>>() {
                        @Override
                        public Task<Uri> then(@NonNull Task<UploadTask.TaskSnapshot> task) throws Exception {
                            if (!task.isSuccessful()) {
                                throw task.getException();
                            }
                            return photoRef.getDownloadUrl();
                        }
                    }).addOnCompleteListener(new OnCompleteListener<Uri>() {
                @Override
                public void onComplete(@NonNull Task<Uri> task) {
                    if (task.isSuccessful()) {
                        Uri downloadUri = task.getResult();
                        Message message = new Message(null, userName, downloadUri.toString());
                        messagesDatabaseReference.push().setValue(message);
                    } else {
                        Log.w(TAG,"Failed");
                    }
                }
            });
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.sign_out_menu:
                //sign out
                AuthUI.getInstance().signOut(this);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }

    }

    @Override
    protected void onPause() {
        super.onPause();
        if (authStateListener != null){
            firebaseAuth.removeAuthStateListener(authStateListener);
        }
        messageAdapter.clear();
        detachDatabaseReadListener();
    }

    @Override
    protected void onResume() {
        super.onResume();
        firebaseAuth.addAuthStateListener(authStateListener);
    }

    private void onSignedInInitialize(String username){
        userName = username;
        attachDatabaseReadListener();
    }

    private void onSignedOutCleanup() {
        userName = ANONYMOUS;
        messageAdapter.clear();
        detachDatabaseReadListener();
    }

    private void attachDatabaseReadListener() {
        if (childEventListener == null) {
            childEventListener = new ChildEventListener() {
                @Override
                public void onChildAdded(@NonNull DataSnapshot snapshot, @Nullable String previousChildName) {
                    Message message = snapshot.getValue(Message.class);
                    messageAdapter.add(message);
                }

                @Override
                public void onChildChanged(@NonNull DataSnapshot snapshot, @Nullable String previousChildName) {

                }

                @Override
                public void onChildRemoved(@NonNull DataSnapshot snapshot) {

                }

                @Override
                public void onChildMoved(@NonNull DataSnapshot snapshot, @Nullable String previousChildName) {

                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {

                }
            };

            messagesDatabaseReference.addChildEventListener(childEventListener);
        }
    }

    private void detachDatabaseReadListener() {
        if (childEventListener != null) {
            messagesDatabaseReference.removeEventListener(childEventListener);
            childEventListener = null;
        }
    }

    /**
     * Fetches the config to determine the length of the msg.
     */
    private void fetchConfig() {
        long caceExpiration = 3600; // an hour
        if (firebaseRemoteConfig.getInfo().getConfigSettings().isDeveloperModeEnabled()) {
            caceExpiration = 0;
        }
        firebaseRemoteConfig.fetch(caceExpiration)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        firebaseRemoteConfig.activateFetched();
                        applyRetrievedLengthLimit();
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Log.w(TAG, "ERROR: fetching config thingy", e);

                        //updates the edit text length limit with the retrieved config.
                        applyRetrievedLengthLimit();
                    }
                });
    }

    /**
     * Apply the retrieved limit of the msg to the edit text field.
     */
    private void applyRetrievedLengthLimit() {
        Long chat_msg_length = firebaseRemoteConfig.getLong(CHAT_MSG_LENGTH_KEY);
        messageEditText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(chat_msg_length.intValue())});
        Log.d(TAG, CHAT_MSG_LENGTH_KEY + " = " + chat_msg_length);
    }
}
