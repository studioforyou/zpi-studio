export type TimedItem<T> = {
  item: T;
  /** Minutes since the day's midnight. */
  start: number;
  end: number;
};

export type PlacedItem<T> = TimedItem<T> & {
  /** 0-based column within its group of overlapping items. */
  column: number;
  /** Number of columns the group of overlapping items needs. */
  columns: number;
};

/**
 * Places the items of one day side by side where they overlap: each group of items that overlap
 * one another directly or through others shares the width equally, and every item takes the
 * first column that is free at its start.
 */
export function layoutDay<T>(items: TimedItem<T>[]): PlacedItem<T>[] {
  const sorted = [...items].sort((a, b) => a.start - b.start || b.end - a.end);
  const placed: PlacedItem<T>[] = [];
  let group: PlacedItem<T>[] = [];
  let columnEnds: number[] = [];
  let groupEnd = -Infinity;

  function closeGroup() {
    for (const member of group) {
      member.columns = columnEnds.length;
    }
    group = [];
    columnEnds = [];
  }

  for (const item of sorted) {
    if (item.start >= groupEnd) {
      closeGroup();
      groupEnd = -Infinity;
    }
    let column = columnEnds.findIndex((end) => end <= item.start);
    if (column === -1) {
      column = columnEnds.length;
      columnEnds.push(item.end);
    } else {
      columnEnds[column] = item.end;
    }
    const entry: PlacedItem<T> = { ...item, column, columns: 1 };
    group.push(entry);
    placed.push(entry);
    groupEnd = Math.max(groupEnd, item.end);
  }
  closeGroup();
  return placed;
}
