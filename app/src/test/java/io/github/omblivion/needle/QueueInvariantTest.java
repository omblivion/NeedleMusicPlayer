package io.github.omblivion.needle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.omblivion.needle.misc.queue.IndexedSong;
import io.github.omblivion.needle.misc.queue.StaticPlayingQueue;
import io.github.omblivion.needle.model.Song;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Random;

public class QueueInvariantTest {
    @Test public void addAfterEmptyQueueAddsWithoutInvalidIndexAccess() {
        StaticPlayingQueue queue = new StaticPlayingQueue();
        queue.addAfter(-1, song(4));
        assertEquals(1, queue.size());
        assertEquals(4, queue.getPlayingQueue().get(0).id);
        assertConsistent(queue);
    }

    @Test public void insertingBeforeFirstTrackPreservesCurrentTrack() {
        StaticPlayingQueue queue = queueAt(1);
        queue.addAfter(-1, song(4));
        assertEquals(2, queue.getCurrentPosition());
        assertEquals(2, queue.getPlayingQueue().get(queue.getCurrentPosition()).id);
        assertConsistent(queue);
    }

    @Test public void insertingImmediatelyBeforeCurrentPreservesCurrentTrack() {
        StaticPlayingQueue queue = queueAt(1);
        queue.addAfter(0, song(4));
        assertEquals(2, queue.getCurrentPosition());
        assertEquals(2, queue.getPlayingQueue().get(queue.getCurrentPosition()).id);
        assertConsistent(queue);
    }

    @Test public void clearInvalidatesBothCurrentAndPreparedPositions() {
        StaticPlayingQueue queue = queueAt(1);
        queue.setNextPosition(2);
        queue.clear();
        assertEquals(-1, queue.getCurrentPosition());
        queue.setPositionToNextPosition();
        assertEquals(-1, queue.getCurrentPosition());
    }

    @Test public void emptyQueueNeverSelectsTrackThroughShuffleOrRepeat() {
        StaticPlayingQueue queue = new StaticPlayingQueue();
        for (int repeat = 0; repeat <= 2; repeat++) {
            queue.setRepeatMode(repeat);
            queue.setShuffle(StaticPlayingQueue.SHUFFLE_MODE_SHUFFLE);
            assertEquals(-1, queue.getCurrentPosition());
            assertEquals(-1, queue.getNextPosition(false));
            assertEquals(-1, queue.getPreviousPosition(false));
            queue.setShuffle(StaticPlayingQueue.SHUFFLE_MODE_NONE);
        }
    }

    @Test public void removingTrackAfterUndoingShuffleKeepsBothOrdersConsistent() {
        StaticPlayingQueue queue = queueAt(1);
        queue.toggleShuffle();
        queue.toggleShuffle();
        queue.remove(0);
        assertEquals(2, queue.size());
        assertEquals(2, queue.getPlayingQueue().get(queue.getCurrentPosition()).id);
        assertConsistent(queue);
    }

    @Test public void restoredMissingFinalCurrentTrackSelectsPreviousSurvivor() {
        ArrayList<IndexedSong> saved = saved(song(1), song(2), Song.EMPTY_SONG);
        StaticPlayingQueue queue = new StaticPlayingQueue(saved, saved(song(1), song(2), Song.EMPTY_SONG),
                2, 0, 0);
        queue.dropUnresolvedEntries();
        assertEquals(2, queue.size());
        assertEquals(1, queue.getCurrentPosition());
        assertConsistent(queue);
    }

    @Test public void restoredAllMissingTracksLeavesNoSelection() {
        StaticPlayingQueue queue = new StaticPlayingQueue(saved(Song.EMPTY_SONG, Song.EMPTY_SONG),
                saved(Song.EMPTY_SONG, Song.EMPTY_SONG), 1, 0, 0);
        queue.dropUnresolvedEntries();
        assertEquals(0, queue.size());
        assertEquals(-1, queue.getCurrentPosition());
    }

    @Test public void batchRemovalReturnsFinalReplacementPosition() {
        StaticPlayingQueue queue = queueAt(2);
        queue.add(song(4));
        int replacement = queue.removeSongs(List.of(song(3), song(1)));
        assertEquals(1, replacement);
        assertEquals(1, queue.getCurrentPosition());
        assertEquals(4, queue.getPlayingQueue().get(replacement).id);
        assertConsistent(queue);
    }

    @Test public void removingOneDuplicatePreservesTheOtherOccurrence() {
        StaticPlayingQueue queue = new StaticPlayingQueue();
        queue.openQueue(List.of(song(1), song(1), song(2)), 1, 0);
        long occurrence = queue.getPlayingQueue().get(1).getUniqueId();
        queue.toggleShuffle();
        queue.toggleShuffle();
        queue.remove(0);
        assertEquals(occurrence, queue.getPlayingQueue().get(queue.getCurrentPosition()).getUniqueId());
        assertEquals(2, queue.size());
        assertConsistent(queue);
    }

    @Test public void openingExistingQueueViewDoesNotEraseItsInput() {
        StaticPlayingQueue queue = queueAt(0);
        assertTrue(queue.openQueue(queue.getPlayingQueue(), 2, 0));
        assertEquals(3, queue.size());
        assertEquals(3, queue.getPlayingQueue().get(queue.getCurrentPosition()).id);
        assertConsistent(queue);
    }

    @Test public void corruptRestoredMappingFailsWithRecoverableValidationError() {
        ArrayList<IndexedSong> queue = saved(song(1), song(2));
        queue.get(1).index = -1;
        assertThrows(IllegalArgumentException.class,
                () -> new StaticPlayingQueue(queue, saved(song(1), song(2)), 0, 1, 0));
    }

    @Test public void duplicateRestoredMappingIsRejected() {
        ArrayList<IndexedSong> queue = saved(song(1), song(2));
        queue.get(1).index = 0;
        assertThrows(IllegalArgumentException.class,
                () -> new StaticPlayingQueue(queue, saved(song(1), song(2)), 0, 1, 0));
    }

    @Test public void invalidNegativePositionDoesNotPoisonSelection() {
        StaticPlayingQueue queue = queueAt(1);
        queue.setCurrentPosition(-2);
        assertEquals(1, queue.getCurrentPosition());
        queue.setNextPosition(2);
        queue.setNextPosition(-2);
        queue.setPositionToNextPosition();
        assertEquals(2, queue.getCurrentPosition());
    }

    @Test public void mixedEditsPreserveOccurrencesAndUnaffectedCurrentTrack() {
        StaticPlayingQueue queue = queueAt(1);
        Random random = new Random(20261115);
        for (int step = 0; step < 500; step++) {
            long selected = queue.getCurrentPosition() >= 0
                    ? queue.getPlayingQueue().get(queue.getCurrentPosition()).getUniqueId() : -1;
            boolean removedSelected = false;
            int operation = queue.size() == 0 ? 0 : random.nextInt(5);
            switch (operation) {
                case 0:
                    queue.addAfter(random.nextInt(queue.size() + 1) - 1, song(random.nextInt(5)));
                    break;
                case 1:
                    int removed = random.nextInt(queue.size());
                    removedSelected = removed == queue.getCurrentPosition();
                    queue.remove(removed);
                    break;
                case 2:
                    queue.move(random.nextInt(queue.size()), random.nextInt(queue.size()));
                    break;
                case 3:
                    queue.toggleShuffle();
                    break;
                default:
                    queue.addAllAfter(random.nextInt(queue.size() + 1) - 1,
                            List.of(song(random.nextInt(5)), song(random.nextInt(5))));
                    break;
            }
            assertConsistent(queue);
            if (selected >= 0 && !removedSelected) {
                assertEquals(selected, queue.getPlayingQueue().get(queue.getCurrentPosition()).getUniqueId());
            }
        }
    }

    @Test public void preparedNextOccurrenceSurvivesInsertionBeforeIt() {
        StaticPlayingQueue queue = queueAt(0);
        queue.setNextPosition(2);
        queue.addAfter(0, song(4));
        queue.setPositionToNextPosition();
        assertEquals(3, queue.getPlayingQueue().get(queue.getCurrentPosition()).id);
    }

    @Test public void preparedNextOccurrenceSurvivesMovingIt() {
        StaticPlayingQueue queue = queueAt(0);
        queue.setNextPosition(2);
        queue.move(2, 0);
        queue.setPositionToNextPosition();
        assertEquals(3, queue.getPlayingQueue().get(queue.getCurrentPosition()).id);
    }

    @Test public void preparedNextOccurrenceSurvivesRemovingEarlierTrack() {
        StaticPlayingQueue queue = queueAt(0);
        queue.setNextPosition(2);
        queue.remove(0);
        queue.setPositionToNextPosition();
        assertEquals(1, queue.getCurrentPosition());
        assertEquals(3, queue.getPlayingQueue().get(queue.getCurrentPosition()).id);
    }

    @Test public void removingPreparedOccurrenceInvalidatesItsPosition() {
        StaticPlayingQueue queue = queueAt(0);
        queue.setNextPosition(2);
        queue.remove(2);
        queue.setPositionToNextPosition();
        assertEquals(-1, queue.getCurrentPosition());
    }

    @Test public void preparedNextOccurrenceSurvivesShuffleReordering() {
        StaticPlayingQueue queue = queueAt(2);
        queue.setNextPosition(0);
        queue.toggleShuffle();
        queue.setPositionToNextPosition();
        assertEquals(1, queue.getPlayingQueue().get(queue.getCurrentPosition()).id);
    }

    private static StaticPlayingQueue queueAt(int current) {
        StaticPlayingQueue queue = new StaticPlayingQueue();
        queue.openQueue(List.of(song(1), song(2), song(3)), current, 0);
        return queue;
    }

    private static Song song(long id) {
        return new Song(id, "Track " + id, 1, 2024, 1000, "/Music/" + id + ".mp3",
                0, 0, 1, "Album", new ArrayList<>(List.of("Artist")));
    }

    private static ArrayList<IndexedSong> saved(Song... songs) {
        ArrayList<IndexedSong> result = new ArrayList<>();
        for (int i = 0; i < songs.length; i++) result.add(new IndexedSong(songs[i], i, i));
        return result;
    }

    private static void assertConsistent(StaticPlayingQueue queue) {
        assertEquals(queue.size(), queue.getOriginalPlayingQueue().size());
        Set<Integer> indexes = new HashSet<>();
        Set<Long> occurrences = new HashSet<>();
        for (int i = 0; i < queue.size(); i++) {
            IndexedSong song = queue.getPlayingQueue().get(i);
            assertTrue(song.index >= 0 && song.index < queue.size());
            assertTrue(indexes.add(song.index));
            assertTrue(occurrences.add(song.getUniqueId()));
            assertEquals(i, queue.getOriginalPlayingQueue().get(i).index);
            assertEquals(song.id, queue.getOriginalPlayingQueue().get(song.index).id);
            assertEquals(song.getUniqueId(), queue.getOriginalPlayingQueue().get(song.index).getUniqueId());
        }
        assertTrue(queue.getCurrentPosition() >= -1 && queue.getCurrentPosition() < queue.size());
    }
}
