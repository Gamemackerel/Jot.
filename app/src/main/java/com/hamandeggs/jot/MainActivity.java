package com.hamandeggs.jot;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.Looper;
import android.support.v4.app.NotificationCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.app.AppCompatDelegate;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Scanner;
import java.util.Stack;

public class MainActivity extends AppCompatActivity implements OnKeyboardVisibilityListener {
    private static final String APP_PREFERENCES = "preferences";
    private static final String QUINTAP_PREF = "clear_notes_choice";
    private static final int ALWAYS_CLEAR = 2;
    private static final int ALWAYS_IGNORE = 1;
    private static final String TAG = "console";
    private static final String DATA_FILENAME = "jotdata.txt";
    private static final int LONGTAP_DURATION = 2500;
    private static final String NOTIFICATION_CHANNEL_ID = "jot_notifications";
    private static final String NOTIFICATION_CHANNEL_NAME = "Jot. notifications";
    private static final String NOTIFICATION_CHANNEL_DESC = "User made notifications from jot notepad";



    private TextWatcher noteListener;
    private Thread longTapTimer;
    private boolean noteChanged = false;
    private int tapCounter = 0;
    private int notificationCounter = 0;
    private NotificationManager notificationManager;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppCompatDelegate.setDefaultNightMode(
                AppCompatDelegate.MODE_NIGHT_AUTO);

        setContentView(R.layout.activity_main);
        EditText jotter = getJotter();

        if(jotter.requestFocus()) {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        createNotificationChannel();
        setKeyboardVisibilityListener(this);
    }

    @Override
    protected void onPause() {
        saveNote();
        removeNoteChangeListener();
        super.onPause();
    }

    @Override
    protected void onResume() {
        loadNote();
        setNoteChangeListener();
        generateNotificationsFromNote(getJotter().getText().toString());
        super.onResume();
    }

    // When the keyboard is put away, save the note to drive (if it has changed)
    // When the keyboard is pulled up, listen for a change to notes, and set the
    // value of field noteChanged appropriately
    @Override
    public void onKeyboardVisibilityChanged(boolean visible) {
        if (!visible) {
            saveNote();
        }
    }

    // On first touch, start a 1 second countdown to
    // reset tapCounter and set tapCounter to 1
    // on subsequent touches , increment tapcounter.
    // If tapCounter reaches 5 then a
    // quintuple tap has occured, execute quintupleTapAction()
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        setQuintupleTapListener(event);
        setLongTapListener(event);
        return super.dispatchTouchEvent(event);
    }

    private void setLongTapListener(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            longTapTimer = new Thread() {
                @Override
                public void run() {
                    Looper.prepare();
                    try {
                        sleep(LONGTAP_DURATION);
                        onLongTap();
                        Looper.loop();
                    } catch (InterruptedException e) {
                        //do nothing
                    }
                }
            };
            longTapTimer.start();
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            longTapTimer.interrupt();
        }
    }

    public void onLongTap() {
        copyNote();
    }

    private void setQuintupleTapListener(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (tapCounter == 0) {
                new Thread() {
                    @Override
                    public void run() {
                        try {
                            sleep(1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        tapCounter = 0;
                    }
                }.start();
            }
            tapCounter++;

            if (tapCounter == 5) {
                OnQuintupleTap();
            }
        }
    }

    private void OnQuintupleTap() {
        SharedPreferences prefs = getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE);
        int rememberChoice = prefs.getInt(QUINTAP_PREF,0);
        if(rememberChoice == ALWAYS_CLEAR) {
            clearNote();
        } else if(rememberChoice == 0) {
            clearNotesDialogue();
        }
    }

    private void clearNotesDialogue() {
        View checkBoxView = View.inflate(this, R.layout.dialog_app_updates, null);
        final CheckBox remember = checkBoxView.findViewById(R.id.checkBox);

        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setTitle("Clear Notes");
        builder.setMessage("Are you sure that you want to clear the notepad?");
        builder.setView(checkBoxView);
        builder.setPositiveButton("YES", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                clearNote();
                if (remember.isChecked()) {
                    SharedPreferences prefs = getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE);
                    prefs.edit().putInt(QUINTAP_PREF, ALWAYS_CLEAR).apply();
                }
                dialog.dismiss();
            }
        });
        builder.setNegativeButton("NO", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (remember.isChecked()) {
                    SharedPreferences prefs = getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE);
                    prefs.edit().putInt(QUINTAP_PREF, ALWAYS_IGNORE).apply();
                }
                dialog.dismiss();
            }
        });
        builder.create().show();
    }

    private void loadNote() {
        EditText jotter = getJotter();
        File directory = this.getFilesDir();
        File dataFile = new File(directory, DATA_FILENAME);
        if (dataFile.exists()) {
            try {
                String textToLoad = readTextFile(dataFile);
                jotter.setText(textToLoad);
            } catch (IOException e) {
                simpleToast("failure while loading note");
                Log.e(TAG, "file read failure", e);
            }
        }
    }

    private void saveNote() {
        String textToSave = getJotter().getText().toString();
        File directory = this.getFilesDir();
        File dataFile = new File(directory, DATA_FILENAME);
        try {
            if (!dataFile.exists() || noteChanged) {
                FileWriter myWriter = new FileWriter(dataFile);
                myWriter.write(textToSave);
                myWriter.close();
                setNoteChangeListener();
            }
        } catch (IOException e) {
            simpleToast("failure while saving note");
            Log.e(TAG, "file write failure", e);
        }
    }

    private EditText getJotter() {
        return (EditText) findViewById(R.id.jotter);
    }

    private void simpleToast(CharSequence message) {
        Context context = getApplicationContext();
        int duration = Toast.LENGTH_SHORT;
        Toast toast = Toast.makeText(context, message, duration);
        toast.show();
    }

    private static String readTextFile(File file) throws IOException {
        Scanner s = new Scanner(file);
        s.useDelimiter("\\A");
        String text = "";
        if (s.hasNext()) {
            text = s.next();

        }
        s.close();
        return text;
    }

    // Set a listener for when the keyboard gets opened or close. Code written by Hiren Patel for a StackOverflow answer
    // https://stackoverflow.com/questions/4312319/how-to-capture-the-virtual-keyboard-show-hide-event-in-android#answer-36259261
    private void setKeyboardVisibilityListener(final OnKeyboardVisibilityListener onKeyboardVisibilityListener) {
        final View parentView = ((ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        parentView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {

            private boolean alreadyOpen;
            private final int defaultKeyboardHeightDP = 100;
            private final int EstimatedKeyboardDP = defaultKeyboardHeightDP + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ? 48 : 0);
            private final Rect rect = new Rect();

            @Override
            public void onGlobalLayout() {
                int estimatedKeyboardHeight = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, EstimatedKeyboardDP, parentView.getResources().getDisplayMetrics());
                parentView.getWindowVisibleDisplayFrame(rect);
                int heightDiff = parentView.getRootView().getHeight() - (rect.bottom - rect.top);
                boolean isShown = heightDiff >= estimatedKeyboardHeight;

                if (isShown == alreadyOpen) {
                    Log.i("Keyboard state", "Ignoring global layout change...");
                    return;
                }
                alreadyOpen = isShown;
                onKeyboardVisibilityListener.onKeyboardVisibilityChanged(isShown);
            }
        });
    }

    // Sets noteChanged to true if any changes are made to the note,
    // and generate user made notifications if newline was detected
    private void setNoteChangeListener() {
        noteChanged = false;
        noteListener = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                //set notechanged to true so note is eligible for saving
                noteChanged = true;

                //if enter was pressed, generate new notifications
                if (contains(s.subSequence(start, start + count), '\n')) {
                    generateNotificationsFromNote(getJotter().getText().toString());
                }
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        };
        getJotter().addTextChangedListener(noteListener);
    }

    private static boolean contains(CharSequence s, char c) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                return true;
            }
        }
        return false;
    }

    private void removeNoteChangeListener() {
        getJotter().removeTextChangedListener(noteListener);
    }

    private void clearNote() {
        getJotter().setText("");
        generateNotificationsFromNote("");
        simpleToast("note cleared");
    }

    private void copyNote() {
        String text = getJotter().getText().toString();
        ClipboardManager clipboard = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("simple text", text);
        clipboard.setPrimaryClip(clip);
        simpleToast("note copied");
    }

    // Find all occurences of '--' at the start of line in note
    // Any line prefixed with this is a push line
    // Creates notifications for each push line from note,
    // minus the '--' prefix
    private void generateNotificationsFromNote(String text) {
        int oldCount = notificationCounter;
        notificationCounter = 0;
        Stack<String> messages = new Stack<>();
        Scanner s = new Scanner(text);
        while(s.hasNextLine()) {
            String thisLine = s.nextLine();
            if (thisLine.length() > 2 && thisLine.charAt(0) == '-' && thisLine.charAt(1) == '-') {
                messages.push(thisLine.substring(2));
            }
        }

        // push notifications in reverse order that they were found in text
        while (!messages.empty()) {
            makePushNotification(messages.pop());
        }

        // remove notifications that no longer exist
        for (int i = notificationCounter + 1; i <= oldCount; i++) {
            notificationManager.cancel(i);
        }

        s.close();
    }

    private void makePushNotification(String message) {
        // Create an explicit intent for mainactivity
        // create a notification with the given message
        // onclick the message opens mainactivity
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(message))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true);
        notificationManager.notify(++notificationCounter, mBuilder.build());
    }

    private void createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME, importance);
            channel.setDescription(NOTIFICATION_CHANNEL_DESC);
            notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }


}