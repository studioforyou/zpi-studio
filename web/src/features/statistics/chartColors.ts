/**
 * Colours of the statistics charts. The categorical colours are validated together for colour
 * vision deficiencies (each pair stays apart); aqua and the light scale steps are below 3:1 against
 * the background, so every chart also has a legend and a table with the same numbers. The panel
 * uses the light colour scheme only.
 */
export const chartColors = {
  occupancy: '#2a78d6',
  attendance: '#1baf7a',
  /** Context, not a finding: attended places next to the lost ones. */
  attended: '#c3c2b7',
  absent: '#eb6834',
  cancelledLate: '#6250d6',
  cancelledInTime: '#1baf7a',
  /** Unfilled part of an occupancy meter: a light step of the occupancy blue. */
  track: '#cde2fb',
  /** Background of a heatmap cell with classes without a limit of places. */
  noLimit: '#f0efec',
};

/** Occupancy scale, light to dark, for below 20%, 20-40%, 40-60%, 60-80% and 80% or more. */
export const occupancyScale = ['#86b6ef', '#5598e7', '#2a78d6', '#1c5cab', '#104281'];

/** Text readable on each occupancy scale step. */
export const occupancyScaleText = ['#0b0b0b', '#0b0b0b', '#ffffff', '#ffffff', '#ffffff'];
