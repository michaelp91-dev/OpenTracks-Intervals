package de.dennisguse.opentracks.settings;

import android.os.Bundle;

import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;

import de.dennisguse.opentracks.R;

public class AnnouncementsSettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.settings_announcements);
        androidx.preference.Preference forget = findPreference(getString(R.string.heart_rate_forget_strap_key));
        if (forget != null) {
            forget.setOnPreferenceClickListener(p -> {
                de.dennisguse.opentracks.intervals.WhoopHeartRateMonitor monitor = de.dennisguse.opentracks.intervals.WhoopHeartRateMonitor.get(requireContext());
                String saved = monitor.getSavedAddress();
                monitor.forgetStrap();
                android.widget.Toast.makeText(requireContext(), saved != null ? "Forgot strap " + saved : "No strap was saved", android.widget.Toast.LENGTH_LONG).show();
                return true;
            });
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        ((SettingsActivity) getActivity()).getSupportActionBar().setTitle(R.string.settings_announcements_statistics_title);
    }

    @Override
    public void onResume() {
        super.onResume();

        ListPreference voiceFrequency = findPreference(getString(R.string.voice_announcement_frequency_key));
        voiceFrequency.setEntries(PreferencesUtils.getVoiceAnnouncementFrequencyEntries());

        ListPreference voiceDistance = findPreference(getString(R.string.voice_announcement_distance_key));
        voiceDistance.setEntries(PreferencesUtils.getVoiceAnnouncementDistanceEntries());
    }
}
