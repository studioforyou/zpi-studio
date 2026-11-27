package studioforyou.pl.backend.files.internal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The names at the top of an OLE compound file (Compound File Binary), the format of Word and
 * Excel files from before 2007 ({@code .doc}, {@code .xls}): a small file system in sectors of
 * 512 or 4096 bytes, with a directory of streams and storages. Only the directory is read, never
 * the streams; a broken file, whose sector chains run out of the file or in circles, is no
 * compound file.
 */
final class CompoundFile {

	static final byte[] SIGNATURE = { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A,
			(byte) 0xE1 };

	/** Ends a chain of sectors. */
	private static final int END_OF_CHAIN = -2;

	/** No sibling or child in the directory. */
	private static final int NO_ENTRY = -1;

	/** Sector numbers of the first FAT sectors, kept in the header. */
	private static final int HEADER_FAT_SECTORS = 109;

	private static final int ENTRY_SIZE = 128;

	private static final int ROOT_STORAGE = 5;

	private CompoundFile() {
	}

	/**
	 * The names of the streams and storages right under the root, or {@code null} when the
	 * content is not a valid compound file.
	 */
	static @Nullable Set<String> rootNames(byte[] content) {
		if (content.length < 512 || !Arrays.equals(content, 0, SIGNATURE.length, SIGNATURE, 0, SIGNATURE.length)) {
			return null;
		}
		ByteBuffer header = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);
		int shift = header.getShort(0x1E) & 0xFFFF;
		if ((header.getShort(0x1C) & 0xFFFF) != 0xFFFE || (shift != 9 && shift != 12)) {
			return null;
		}
		int size = 1 << shift;
		// Some programs leave the last sector short.
		byte[] file = Arrays.copyOf(content, (content.length + size - 1) / size * size);
		try {
			return rootNames(file, size);
		}
		catch (IndexOutOfBoundsException ex) {
			return null;
		}
	}

	private static @Nullable Set<String> rootNames(byte[] file, int size) {
		ByteBuffer bytes = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
		Sectors sectors = new Sectors(bytes, size);
		List<Integer> fat = fatSectors(bytes, sectors);
		if (fat == null) {
			return null;
		}
		List<Integer> directory = sectors.chain(bytes.getInt(0x30), fat);
		if (directory == null || directory.isEmpty()) {
			return null;
		}
		int perSector = sectors.size() / ENTRY_SIZE;
		int entries = directory.size() * perSector;
		int root = sectors.offset(directory.get(0));
		if ((bytes.get(root + 0x42) & 0xFF) != ROOT_STORAGE) {
			return null;
		}
		// The root's children are a tree of siblings; their own children are left out.
		Set<String> names = new HashSet<>();
		Set<Integer> seen = new HashSet<>();
		Deque<Integer> next = new ArrayDeque<>(List.of(bytes.getInt(root + 0x4C)));
		while (!next.isEmpty()) {
			int id = next.pop();
			if (id == NO_ENTRY) {
				continue;
			}
			if (id < 0 || id >= entries || !seen.add(id)) {
				return null;
			}
			int entry = sectors.offset(directory.get(id / perSector)) + (id % perSector) * ENTRY_SIZE;
			int nameLength = bytes.getShort(entry + 0x40) & 0xFFFF;
			if (nameLength < 2 || nameLength > 64 || nameLength % 2 != 0) {
				return null;
			}
			names.add(new String(file, entry, nameLength - 2, StandardCharsets.UTF_16LE));
			next.push(bytes.getInt(entry + 0x44));
			next.push(bytes.getInt(entry + 0x48));
		}
		return names;
	}

	/**
	 * The sectors of the file allocation table, which says which sector follows which: the first
	 * ones listed in the header, the rest in a chain of DIFAT sectors.
	 */
	private static @Nullable List<Integer> fatSectors(ByteBuffer bytes, Sectors sectors) {
		int count = bytes.getInt(0x2C);
		if (count <= 0 || count > sectors.count()) {
			return null;
		}
		List<Integer> fat = new ArrayList<>();
		for (int i = 0; i < HEADER_FAT_SECTORS && fat.size() < count; i++) {
			fat.add(bytes.getInt(0x4C + 4 * i));
		}
		int difat = bytes.getInt(0x44);
		Set<Integer> seen = new HashSet<>();
		while (fat.size() < count) {
			if (!sectors.exists(difat) || !seen.add(difat)) {
				return null;
			}
			int offset = sectors.offset(difat);
			int perSector = sectors.size() / 4 - 1;
			for (int i = 0; i < perSector && fat.size() < count; i++) {
				fat.add(bytes.getInt(offset + 4 * i));
			}
			difat = bytes.getInt(offset + 4 * perSector);
		}
		return fat.stream().allMatch(sectors::exists) ? fat : null;
	}

	/** The sectors of the file, after its header, which takes up the first sector. */
	private record Sectors(ByteBuffer bytes, int size) {

		int count() {
			return bytes.capacity() / size - 1;
		}

		boolean exists(int sector) {
			return sector >= 0 && sector < count();
		}

		int offset(int sector) {
			return (sector + 1) * size;
		}

		/** The sectors of a chain starting at {@code first}, or {@code null} when it is broken. */
		@Nullable List<Integer> chain(int first, List<Integer> fat) {
			int perFatSector = size / 4;
			List<Integer> chain = new ArrayList<>();
			int sector = first;
			while (sector != END_OF_CHAIN) {
				if (!exists(sector) || chain.size() >= count() || sector / perFatSector >= fat.size()) {
					return null;
				}
				chain.add(sector);
				sector = bytes.getInt(offset(fat.get(sector / perFatSector)) + 4 * (sector % perFatSector));
			}
			return chain;
		}

	}

}
