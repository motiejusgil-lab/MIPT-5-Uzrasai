package lt.mipt.notes.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import lt.mipt.notes.R;
import lt.mipt.notes.model.Note;

/**
 * ListView adapter displaying live notes with title, content preview, and localized update time.
 */
public final class NoteListAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<Note> notes = new ArrayList<>();

    public NoteListAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    public void setNotes(List<Note> newNotes) {
        notes.clear();
        notes.addAll(newNotes);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return notes.size();
    }

    @Override
    public Note getItem(int position) {
        return notes.get(position);
    }

    @Override
    public long getItemId(int position) {
        return notes.get(position).getId().hashCode();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        ViewHolder holder;
        if (view == null) {
            view = inflater.inflate(R.layout.item_note, parent, false);
            holder = new ViewHolder(view);
            view.setTag(holder);
        } else {
            holder = (ViewHolder) view.getTag();
        }

        Note note = getItem(position);
        Context context = view.getContext();
        holder.title.setText(note.getTitle());
        holder.content.setText(note.getContent());
        String formattedTime = UiErrorHelper.formatTimestamp(note.getUpdatedAt());
        holder.updated.setText(context.getString(R.string.updated_at_format, formattedTime));
        return view;
    }

    private static final class ViewHolder {
        final TextView title;
        final TextView content;
        final TextView updated;

        ViewHolder(View itemView) {
            title = itemView.findViewById(R.id.text_item_title);
            content = itemView.findViewById(R.id.text_item_content);
            updated = itemView.findViewById(R.id.text_item_updated);
        }
    }
}
