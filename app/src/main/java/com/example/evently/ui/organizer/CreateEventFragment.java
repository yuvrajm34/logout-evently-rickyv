package com.example.evently.ui.organizer;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.bumptech.glide.Glide;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.google.firebase.Timestamp;

import com.example.evently.R;
import com.example.evently.data.EventsDB;
import com.example.evently.data.generic.Promise;
import com.example.evently.data.model.Category;
import com.example.evently.data.model.Event;
import com.example.evently.databinding.FragmentCreateEventBinding;
import com.example.evently.utils.FirebaseAuthUtils;

/**
 * Fragment that collects input to create a new organizer-owned {@link Event}
 * <p>
 * It inflates {@code R.layout.fragment_create_event}, performs simple client-side
 * validation, and on success constructs an {@link Event} object and returns it to
 * the previous fragment via the Navigation component's {@code SavedStateHandle}
 * under the key {@code "new_event"}, then calls {@code navigateUp()} to return.
 * Persistence is handled by the receiving screen.
 */
public class CreateEventFragment extends Fragment {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm");

    private FragmentCreateEventBinding binding;

    private Uri imageUri;
    private ImageButton imageButton;

    // The following code defines a launcher to pick a picture. For more details, see the android
    // photo picker docs:
    // https://developer.android.com/training/data-storage/shared/photo-picker
    private final ActivityResultLauncher<PickVisualMediaRequest> pickPoster =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    imageUri = uri;
                    Glide.with(getContext()).load(imageUri).into(imageButton);
                } else {
                    Log.d("Poster Picker", "No poster selected");
                }
            });

    @Nullable private LocalDate selectionDeadline;
    @Nullable private LocalDate eventDate;
    @Nullable private LocalTime eventTime;


    /**
     * Inflates the "Create Event" form
     *
     * @param inflater The LayoutInflater object that can be used to inflate
     * any views in the fragment,
     * @param container If non-null, this is the parent view that the fragment's
     * UI should be attached to.  The fragment should not add the view itself,
     * but this can be used to generate the LayoutParams of the view.
     * @param savedInstanceState If non-null, this fragment is being re-constructed
     * from a previous saved state as given here.
     *
     * @return the view of the inflated form
     */
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = FragmentCreateEventBinding.inflate(getLayoutInflater(), container, false);
        return binding.getRoot();
    }

    /**
     * Builds up a form, validates input, builds an {@link Event}, returns it, and navigates up
     *
     * @param v The View returned by {@link #onCreateView(LayoutInflater, ViewGroup, Bundle)}
     * @param savedInstanceState If non-null, this fragment is being re-constructed
     * from a previous saved state as given here
     */
    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        final var btnCreate = binding.btnCreate;

        binding.btnCancel.setOnClickListener(
                _x -> NavHostFragment.findNavController(this).navigateUp());

        imageButton = v.findViewById(R.id.btnPickPoster);

        // Launches the poster picker when clicked.
        imageButton.setOnClickListener(view -> {
            pickPoster.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        });

        setupSelectionDeadlinePicker();
        setupEventDatePicker();
        setupEventTimePicker();

        btnCreate.setOnClickListener(_x -> {
            String name = binding.etEventName.getText().toString().trim();
            String desc = binding.etDescription.getText().toString().trim();
            String winnersStr = binding.etWinners.getText().toString().trim();


            if (TextUtils.isEmpty(name)) {
                toast("Please enter an event name.");
                return;
            }
            if (TextUtils.isEmpty(winnersStr)) {
                toast("Please enter number of winners.");
                return;
            }
            if (selectionDeadline == null) {
                toast("Please select a selection deadline date.");
                return;
            }

            if (eventDate == null || eventTime == null) {
                toast("Please select an event date and time.");
                return;
            }

            long winners;
            try {
                winners = Long.parseLong(winnersStr);
            } catch (NumberFormatException e) {
                toast("Winners must be an integer.");
                return;
            }

            Optional<Long> wait = Optional.empty();
            String w = binding.etWaitLimit.getText().toString().trim();
            if (!TextUtils.isEmpty(w)) {
                try {
                    wait = Optional.of(Long.parseLong(w));
                } catch (NumberFormatException e) {
                    toast("Waitlist limit must be an integer.");
                    return;
                }
            }

            final Instant selectionTime = selectionDeadline
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant();
            final Instant eventInstant = LocalDateTime.of(eventDate, eventTime)
                    .atZone(ZoneId.systemDefault())
                    .toInstant();

            if (!eventInstant.isAfter(selectionTime)) {
                toast("Event time must be after the selection deadline.");
                return;
            }

            String organizer = FirebaseAuthUtils.getCurrentEmail();

            // For now, eventTime == selectionTime + 2 days (until organizer add event date/time
            // fields)
            Event created = new Event(
                    name,
                    desc,
                    Category.SPORTS,
                    new Timestamp(selectionTime),
                    new Timestamp(eventInstant),
                    FirebaseAuthUtils.getCurrentEmail(),
                    winners);

            btnCreate.setEnabled(false);

            EventsDB eventsDB = new EventsDB();

            // Gets the upload promise if an image has been selected through the photo picker.
            var uploadPromise = imageUri != null
                    ? eventsDB.storePoster(created.eventID(), imageUri)
                    : Promise.of(null);

            // Stores the event, and uploads the poster.
            eventsDB.storeEvent(created)
                    .alongside(uploadPromise)
                    .thenRun(_v -> {
                        toast("Event created.");
                        NavHostFragment.findNavController(this).navigateUp();
                    })
                    .catchE(e -> {
                        btnCreate.setEnabled(true);
                        toast("Failed to save event. Try again.");
                    });
        });
    }

    private void setupSelectionDeadlinePicker() {
        final View.OnClickListener listener = _v -> {
            final var pickerBuilder = MaterialDatePicker.Builder.datePicker();
            pickerBuilder.setTitleText("Select selection deadline");

            if (selectionDeadline != null) {
                pickerBuilder.setSelection(toEpochMillis(selectionDeadline));
            }

            final var picker = pickerBuilder.build();
            picker.addOnPositiveButtonClickListener(selection -> {
                if (selection != null) {
                    selectionDeadline = toLocalDate(selection);
                    binding.etSelectionDeadline.setText(
                            DATE_FORMATTER.format(selectionDeadline));
                }
            });

            picker.show(getParentFragmentManager(), "selection_deadline_picker");
        };

        binding.tilSelectionDeadline.setOnClickListener(listener);
        binding.etSelectionDeadline.setOnClickListener(listener);
    }

    private void setupEventDatePicker() {
        final View.OnClickListener listener = _v -> {
            final var pickerBuilder = MaterialDatePicker.Builder.datePicker();
            pickerBuilder.setTitleText("Select event date");

            if (eventDate != null) {
                pickerBuilder.setSelection(toEpochMillis(eventDate));
            }

            final var picker = pickerBuilder.build();
            picker.addOnPositiveButtonClickListener(selection -> {
                if (selection != null) {
                    eventDate = toLocalDate(selection);
                    binding.etEventDate.setText(DATE_FORMATTER.format(eventDate));
                }
            });

            picker.show(getParentFragmentManager(), "event_date_picker");
        };

        binding.tilEventDate.setOnClickListener(listener);
        binding.etEventDate.setOnClickListener(listener);
    }

    private void setupEventTimePicker() {
        final View.OnClickListener listener = _v -> {
            final var builder = new MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_24H);

            if (eventTime != null) {
                builder.setHour(eventTime.getHour());
                builder.setMinute(eventTime.getMinute());
            }

            final var picker = builder.build();
            picker.addOnPositiveButtonClickListener(_selection -> {
                eventTime = LocalTime.of(picker.getHour(), picker.getMinute());
                binding.etEventTime.setText(TIME_FORMATTER.format(eventTime));
            });

            picker.show(getParentFragmentManager(), "event_time_picker");
        };

        binding.tilEventTime.setOnClickListener(listener);
        binding.etEventTime.setOnClickListener(listener);
    }

    private LocalDate toLocalDate(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
    }

    private long toEpochMillis(LocalDate date) {
        return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }


    /**
     * Shows a short-length {@link Toast} with the given message in this Fragment's context
     * @param msg message to display to the user
     */
    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
