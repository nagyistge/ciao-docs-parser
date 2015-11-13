package uk.nhs.ciao.docs.parser;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import uk.nhs.ciao.util.SimpleEntry;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;

/**
 * Utility methods for handling property paths and segments
 */
final class PropertyPath {
	private PropertyPath() {
		// Suppress default constructor
	}
	
	/**
	 * Represents the state / mode while parsing a path into segments
	 */
	private enum ParseState {
		/**
		 * Before parsing / root path
		 */
		ROOT,
		
		/**
		 * Parsing a keyed segment
		 */
		KEY,
		
		/**
		 * Immediately after a keyed segment delimiter: <code>.</code>
		 */
		AFTER_KEY,
		
		/**
		 * Parsing an indexed segment: e.g. <code>[2]</code>
		 */
		INDEX,
		
		/**
		 * Immediately after an indexed segment closing bracket: <code>]</code>
		 */
		AFTER_INDEX;
	}
	
	/**
	 * Special-case segment for matching any key in a dynamic map
	 */
	public static final Object ANY_KEY = new Object() {
		@Override
		public String toString() {
			return "*";
		}
	};
	
	/**
	 * Special-case segment for matching any index in an array
	 */
	public static final Object ANY_INDEX = new Object() {
		@Override
		public String toString() {
			return "[*]";
		}
	};
	
	/**
	 * Pattern to match a single special character: <code>. [ ] * \</code>
	 */
	private static final Pattern SPECIAL_CHARACTERS_PATTERN = Pattern.compile("([\\.\\[\\]\\*\\\\])");
	
	/**
	 * Parses a path into segments
	 * <p>
	 * Wildcards are not permitted in paths parsed by this method.
	 * 
	 * @param path The path to parse
	 * @return The parsed path segments
	 * @see #parse(String, boolean)
	 */
	public static Object[] parse(final String path) {
		final boolean allowWildcards = false;
		return parse(path, allowWildcards);
	}
	
	/**
	 * Parses a path into segments
	 * <p>
	 * The resulting array contains:
	 * <ul>
	 * <li>String values representing keyed/named segments
	 * <li>Integer values representing indexed segments
	 * </ul>
	 * <p>
	 * If allowed, wildcards (.*, or [*]) are represented using {@link #ANY_KEY} and {@link #ANY_INDEX}.
	 * <p>
	 * An empty array represents the root path
	 * 
	 * @param path The path to parse
	 * @param allowWildcards Whether or not wildcard segments are allowed
	 * @return The parsed path segments
	 */
	public static Object[] parse(final String path, final boolean allowWildcards) {
		if (Strings.isNullOrEmpty(path)) {
			return new Object[0];
		}
		
		final List<Object> segments = Lists.newArrayList();
		final StringBuilder builder = new StringBuilder();
		ParseState state = ParseState.ROOT;
		boolean delimited = false;
		for (int index = 0; index < path.length(); index++) {
			char c = path.charAt(index);
			// delimiter
			if (c == '\\') {
				switch (state) {
				case ROOT:
				case KEY:
				case AFTER_KEY:
					if (delimited) {
						builder.append(c);
						state = ParseState.KEY;
					} else if (index + 1 == path.length()) {
						throw new IllegalArgumentException("Dangling escape character - pos: " + index);
					}
					delimited = !delimited;
					break;
				default:
					throw new IllegalArgumentException("Invalid escape character - pos: " + index);
				}
			} else if (delimited) {
				builder.append(c);
				state = ParseState.KEY;
				delimited = false;
			}
			
			// special characters
			else if (c == '[') {
				if (state == ParseState.KEY) {
					segments.add(builder.toString());
					builder.setLength(0);
				} else if (state != ParseState.ROOT && state != ParseState.AFTER_INDEX) {
					throw new IllegalArgumentException("Invalid start index character - pos: " + index);
				}
				
				state = ParseState.INDEX;
			} else if (c == ']') {
				if (state != ParseState.INDEX) {
					throw new IllegalArgumentException("Invalid close index character - pos: " + index);
				} else if (builder.length() == 0) {
					throw new IllegalArgumentException("Invalid close index character - missing index - pos: " + index);
				}
				
				segments.add(Integer.valueOf(builder.toString()));
				builder.setLength(0);
				
				state = ParseState.AFTER_INDEX;
			} else if (c == '.') {
				if (state == ParseState.KEY) {
					segments.add(builder.toString());
					builder.setLength(0);
				} else if (state != ParseState.AFTER_INDEX) {
					throw new IllegalArgumentException("Invalid key separator - pos: " + index);
				}
				
				state = ParseState.AFTER_KEY;
			} else if (c == '*') {
				if (state == ParseState.INDEX) {
					if (!allowWildcards || builder.length() > 0) {
						throw new IllegalArgumentException("Invalid wildcard - pos: " + index);
					}
					segments.add(ANY_INDEX);
										
					// Look ahead to check closing segment
					index++;
					if (path.length() <= index) {
						continue;
					} else if (path.charAt(index) != ']') {
						throw new IllegalArgumentException("Invalid character - expected close index character - pos: " + index);
					}
					
					state = ParseState.AFTER_INDEX;
				} else if (state == ParseState.ROOT || state == ParseState.AFTER_KEY) {
					if (!allowWildcards || builder.length() > 0) {
						throw new IllegalArgumentException("Invalid wildcard - pos: " + index);
					}
					segments.add(ANY_KEY);
					state = ParseState.KEY;
					
					// Look ahead to check end of path or delimiter
					index++;
					if (path.length() <= index) {
						continue;
					} else if (path.charAt(index) != '.' && path.charAt(index) != '[') {
						throw new IllegalArgumentException("Invalid character - expected delimiter - pos: " + index);
					}
				} else {
					throw new IllegalArgumentException("Invalid wildcard - pos: " + index);
				}
			}
			
			// digits
			else if (c >= '0' && c <= '9') {
				if (state == ParseState.AFTER_INDEX) {
					throw new IllegalArgumentException("Invalid character - expected delimiter - pos: " + index);
				} else if (state != ParseState.INDEX) {
					state = ParseState.KEY;
				}
				builder.append(c);
			}
			
			// standard characters
			else {
				if (state == ParseState.AFTER_INDEX) {
					throw new IllegalArgumentException("Invalid character - expected delimiter - pos: " + index);
				} else if (state == ParseState.INDEX) {
					throw new IllegalArgumentException("Invalid character - expected digit - pos: " + index);
				}
				
				builder.append(c);
				state = ParseState.KEY;
			}
		}
		
		switch (state) {
		case KEY:
			if (builder.length() > 0) {
				segments.add(builder.toString());
				builder.setLength(0);
			}
			break;
		case ROOT:
		case AFTER_INDEX:
			// NOOP
			break;
		default:
			throw new IllegalArgumentException("Incomplete segment in path");
		}
		
		return segments.toArray();
	}
	
	/**
	 * Encodes the specified path segments as a path string
	 * 
	 * @param segments The segments to encode
	 * @return A path string representing the specified segments
	 */
	public static String toString(final Object[] segments) {
		final StringBuilder builder = new StringBuilder();
		for (final Object segment: segments) {
			if (segment instanceof Integer) {
				builder.append('[').append(segment).append(']');
			} else if (segment == ANY_INDEX) {
				builder.append(segment);
			} else {
				if (builder.length() > 0) {
					builder.append('.');
				}
				
				if (segment == ANY_KEY) {
					builder.append(segment);
				} else {
					builder.append(encodeSegment(segment.toString()));
				}
			}
		}
		
		return builder.toString();
	}
	
	/**
	 * Tests if the specified segments contains a wildcard (ANY_KEY or ANY_INDEX)
	 */
	public static boolean containsWildcard(final Object[] segments) {
		for (final Object segment: segments) {
			if (ANY_KEY == segment || ANY_INDEX == segment) {
				return true;
			}
		}
		return false;
	}
	
	public static <T> T getValue(final Class<T> type, final Object source, final Object[] segments) {
		return get(type, source, segments, 0, null);
	}
		
	public static <T> Entry<Object[], T> getEntry(final Class<T> type, final Object source, final Object[] segments) {
		final Object[] resultSegments;
		final T value;
		if (containsWildcard(segments)) {
			resultSegments = new Object[segments.length];
			value = get(type, source, segments, 0, resultSegments);
		} else {
			resultSegments = segments;
			value = get(type, source, segments, 0, null);
		}
		
		return value == null ? null : SimpleEntry.valueOf(resultSegments, value);
	}
	
	@SuppressWarnings("unchecked")
	public static List<Object> getValueAsList(final Object source, final Object[] segments) {
		final Object value = getValue(List.class, source, segments);
		if (value instanceof List) {
			return (List<Object>)value;
		}
		return null;
	}
	
	@SuppressWarnings("unchecked")
	public static Map<String, Object> getValueAsMap(final Object source, final Object[] segments) {
		final Object value = getValue(Map.class, source, segments);
		if (value instanceof Map) {
			return (Map<String, Object>)value;
		}
		return null;
	}
	
	private static <T> T get(final Class<T> type, final Object source, final Object[] segments, final int start, final Object[] resultSegments) {
		if (source == null || start == segments.length) {
			return type.isInstance(source) ? type.cast(source) : null;
		}
		
		T result = null;
		final Object segment = segments[start];
		Object resultSegment = segment;
		if (segment == ANY_INDEX) {
			if (source instanceof List) {
				final List<?> list = (List<?>)source;
				int index = 0;
				for (final Object value: list) {
					result = get(type, value, segments, start + 1, resultSegments);
					if (result != null) {
						resultSegment = index;
						break;
					}
				}
			}
		} else if (segment instanceof Integer) {
			if (source instanceof List) {
				final List<?> list = (List<?>)source;
				final int index = (Integer)segment;
				final Object value = index < list.size() ? list.get(index) : null;
				result = get(type, value, segments, start + 1, resultSegments);
			}
		} else if (source instanceof Map) {
			final Map<?, ?> map = (Map<?, ?>)source;
			if (segment == ANY_KEY) {
				for (final Entry<?, ?> entry: map.entrySet()) {
					result = get(type, entry.getValue(), segments, start + 1, resultSegments);
					if (result != null) {
						resultSegment = entry.getKey();
						break;
					}
				}
			} else {
				result = get(type, map.get(segment), segments, start + 1, resultSegments);
			}
		}
		
		if (result != null && resultSegments != null) {
			resultSegments[start] = resultSegment;
		}
		
		return result;
	}
	
	/**
	 * regex to replace all special characters . [ ] \ with \. \[ \] \\
	 */
	private static String encodeSegment(final String segment) {
		final Matcher matcher = SPECIAL_CHARACTERS_PATTERN.matcher(segment);
		return matcher.replaceAll("\\\\$1");
	}
}
