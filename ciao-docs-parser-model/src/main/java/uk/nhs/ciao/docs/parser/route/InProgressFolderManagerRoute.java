package uk.nhs.ciao.docs.parser.route;

import static org.apache.camel.builder.ExpressionBuilder.append;
import static org.apache.camel.builder.PredicateBuilder.*;
import static uk.nhs.ciao.logging.CiaoCamelLogMessage.camelLogMsg;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import uk.nhs.ciao.camel.BaseRouteBuilder;
import uk.nhs.ciao.logging.CiaoCamelLogger;
import uk.nhs.ciao.util.Clock;
import uk.nhs.ciao.util.SystemClock;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;

/**
 * Provides methods to manage the in-progress folders associated with a document upload.
 * <p>
 * The in-progress folder has the following structure:
 * <p>
 * <pre>
 * source/
 *   ${documentName} - original document content
 * control/
 *   error-folder - single line file specifying location of error folder
 *   completed-folder - single line file specifying location of completed folder
 *   wants-bus-ack - empty file, present if an ITK business ack has been requested
 *   wants-inf-ack - empty file, present if an ITK infrastructure ack has been requested
 * state/
 *   ${timestamp}-${messageType}-${eventType} - file contains the message associated with the event
 * </pre>
 * <p>
 * State event types:
 * <li><code>preparation-failed</code> - a message/document has failed preparation
 * <li><code>sending</code> - a message is being sent
 * <li><code>sent</code> - a message was successfully sent
 * <li><code>send-failed</code> - a message failed to send
 * <li><code>received</code> - a message was received
 * <p>
 * State message types:
 * <ul>
 * <li><code>document</code> - file related to the original source document
 * <li><code>bus-message</code> - file containing an ITK business message
 * <li><code>inf-ack</code> - file containing an ITK infrastructure ack
 * <li><code>bus-ack</code> - file containing an ITK business ack
 * <li><code>inf-nack</code> - file containing an ITK infrastructure nack
 * <li><code>bus-nack</code> - file containing an ITK business nack
 * </ul>
 * <p>
 * The timestamp format is <code>yyyyMMdd-HHmmssSSS</code> (in UTC)
 */
public class InProgressFolderManagerRoute extends BaseRouteBuilder {
	private static final CiaoCamelLogger LOGGER = CiaoCamelLogger.getLogger(InProgressFolderManagerRoute.class);
	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormat.forPattern("yyyyMMdd-HHmmssSSS").withZoneUTC();
	
	/**
	 * Enumeration of header names supported by the manager
	 */
	public static final class Header {
		public static final String ACTION = "ciao.inProgressFolder.action";
		public static final String FILE_TYPE = "ciao.inProgressFolder.fileType";
		public static final String EVENT_TYPE = "ciao.inProgressFolder.eventType";
		
		// Internal (private) headers
		private static final String MESSAGE_TYPE = "ciao.inProgressFolder.messageType";
		
		private Header() {
			// Suppress default constructor
		}
	}
	
	/**
	 * Enumeration of action header values supported by the manager
	 * 
	 * @see Header#ACTION
	 */
	public static final class Action {
		public static final String STORE = "store";
		
		private Action() {
			// Suppress default constructor
		}
	}
	
	/**
	 * Enumeration of file type header values supported by the manager
	 * 
	 * @see Header#FILE_TYPE
	 */
	public static final class FileType {
		public static final String CONTROL = "control";
		public static final String EVENT = "event";
		
		private FileType() {
			// Suppress default constructor
		}
	}
	
	/**
	 * Enumeration of event type header values supported by the manager
	 * 
	 * @see Header#EVENT_TYPE
	 */
	public static final class EventType {
		public static final String MESSAGE_PREPARATION_FAILED = "preparation-failed";
		public static final String MESSAGE_SENDING = "sending";
		public static final String MESSAGE_SENT = "sent";
		public static final String MESSAGE_SEND_FAILED = "send-failed";
		public static final String MESSAGE_RECEIVED = "received";
		
		private EventType() {
			// Suppress default constructor
		}
	}
	
	/**
	 * Enumeration of known message types
	 * <p>
	 * Values are supplied via the {@link Exchange#FILE_NAME} header
	 */
	public static final class MessageType {
		public static final String DOCUMENT = "document";
		public static final String BUSINESS_MESSAGE = "bus-message";
		public static final String INFRASTRUCTURE_ACK = "inf-ack";
		public static final String INFRASTRUCTURE_NACK = "inf-nack";
		public static final String BUSINESS_ACK = "bus-ack";
		public static final String BUSINESS_NACK = "bus-nack";
		
		private MessageType() {
			// Suppress default constructor
		}
	}
	
	private String inProgressFolderManagerUri;
	private String inProgressFolderRootUri;
	private Clock clock = SystemClock.getInstance();
	
	public void setInProgressFolderManagerUri(final String inProgressFolderManagerUri) {
		this.inProgressFolderManagerUri = inProgressFolderManagerUri;
	}
	
	public void setInProgressFolderRootUri(final String inProgressFolderRootUri) {
		this.inProgressFolderRootUri = inProgressFolderRootUri;
	}
	
	public void setClock(final Clock clock) {
		this.clock = Preconditions.checkNotNull(clock);
	}
	
	private String getStoreEventFileUri() {
		return internalDirectUri("store-event-file");
	}
	
	private String getStoreEventFileHandlerUri() {
		return internalDirectUri("store-event-file-handler");
	}
	
	private String getStoreControlFileUri() {
		return internalDirectUri("store-control-file");
	}
	
	@Override
	public void configure() throws Exception {
		configureRouter();
		configureStoreControlFileRoute();
		configureStoreEventFileRoute();
		configureStoreEventFileHandlerRoute();
	}
	
	private void configureRouter() throws Exception {
		from(inProgressFolderManagerUri)
			.choice()
				.when(isEqualTo(header(Header.ACTION), constant(Action.STORE)))
					.pipeline()
						.choice()
							.when(isEqualTo(header(Header.FILE_TYPE), constant(FileType.CONTROL)))
								.to(getStoreControlFileUri())
							.endChoice()
	
							.when(isEqualTo(header(Header.FILE_TYPE), constant(FileType.EVENT)))
								.to(getStoreEventFileUri())
							.endChoice()
						.end()
					.end()
				.endChoice()
			.end()
		.end();
	}
	
	private void configureStoreControlFileRoute() throws Exception {
		from(getStoreControlFileUri())
				
			.bean(new ControlFileNameCalculator())
			.to(inProgressFolderRootUri + "?fileExist=Override")

			.process(LOGGER.info(camelLogMsg("Stored control file in in-progress folder")
					.documentId(header(Exchange.CORRELATION_ID))
					.fileName(header(Exchange.FILE_NAME))))
		.end();
	}
	
	private void configureStoreEventFileRoute() throws Exception {
		from(getStoreEventFileUri())
			.errorHandler(defaultErrorHandler()
				.maximumRedeliveries(10)
				.redeliveryDelay(1))
				
			.to(getStoreEventFileHandlerUri())
			
			.process(LOGGER.info(camelLogMsg("Stored event file in in-progress folder")
					.documentId(header(Exchange.CORRELATION_ID))
					.eventName(append(append(header(Header.MESSAGE_TYPE), constant("-")), header(Header.EVENT_TYPE)))
					.fileName(header(Exchange.FILE_NAME))))
		.end();
	}
	
	private void configureStoreEventFileHandlerRoute() throws Exception {
		from(getStoreEventFileHandlerUri())
			.errorHandler(noErrorHandler())
			
			.bean(new EventFileNameCalculator())
			.to(inProgressFolderRootUri + "?fileExist=Fail")			
		.end();
	}
	
	// Processor / bean methods
	// The methods can't live in the route builder - it causes havoc with the debug/tracer logging
	
	public class ControlFileNameCalculator {
		public void calculateFileName(final Message message) throws Exception {
			final String originalName = Strings.nullToEmpty(message.getHeader(Exchange.FILE_NAME, String.class));
			final String id = message.getHeader(Exchange.CORRELATION_ID, String.class);
			if (Strings.isNullOrEmpty(id)) {
				throw new Exception("Missing header " + Exchange.CORRELATION_ID);
			}

			final String fileName = id + "/control/" + originalName;
			message.setHeader(Exchange.FILE_NAME, fileName);
		}
	}
	
	public class EventFileNameCalculator {
		public void calculateFileName(final Message message) throws Exception {
			final String messageType = Strings.nullToEmpty(message.getHeader(Exchange.FILE_NAME, String.class));
			message.setHeader(Header.MESSAGE_TYPE, messageType);
			
			final String id = message.getHeader(Exchange.CORRELATION_ID, String.class);
			if (Strings.isNullOrEmpty(id)) {
				throw new Exception("Missing header " + Exchange.CORRELATION_ID);
			}

			final String eventType = message.getHeader(Header.EVENT_TYPE, String.class);
			if (Strings.isNullOrEmpty(eventType)) {
				throw new Exception("Missing header " + Header.EVENT_TYPE);
			}
			
			final String timestamp = TIMESTAMP_FORMAT.print(clock.getMillis());
			final String fileName = id + "/events/" + timestamp + "-" + messageType + "-" + eventType;
			message.setHeader(Exchange.FILE_NAME, fileName);
		}
	}
}
