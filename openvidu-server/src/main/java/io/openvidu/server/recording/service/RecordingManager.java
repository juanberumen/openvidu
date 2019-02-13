/*
 * (C) Copyright 2017-2019 OpenVidu (https://openvidu.io/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.openvidu.server.recording.service;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.ws.rs.ProcessingException;

import org.apache.commons.io.FileUtils;
import org.kurento.client.ErrorEvent;
import org.kurento.client.EventListener;
import org.kurento.client.MediaPipeline;
import org.kurento.client.MediaProfileSpecType;
import org.kurento.client.RecorderEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.openvidu.client.OpenViduException;
import io.openvidu.client.OpenViduException.Code;
import io.openvidu.client.internal.ProtocolElements;
import io.openvidu.java.client.RecordingProperties;
import io.openvidu.server.config.OpenviduConfig;
import io.openvidu.server.core.Participant;
import io.openvidu.server.core.Session;
import io.openvidu.server.core.SessionEventsHandler;
import io.openvidu.server.core.SessionManager;
import io.openvidu.server.kurento.KurentoClientProvider;
import io.openvidu.server.kurento.KurentoClientSessionInfo;
import io.openvidu.server.kurento.OpenViduKurentoClientSessionInfo;
import io.openvidu.server.recording.Recording;
import io.openvidu.server.utils.CustomFileManager;

@Service
public class RecordingManager {

	private static final Logger log = LoggerFactory.getLogger(RecordingManager.class);

	RecordingService recordingService;
	private ComposedRecordingService composedRecordingService;
	private SingleStreamRecordingService singleStreamRecordingService;

	@Autowired
	protected SessionEventsHandler sessionHandler;

	@Autowired
	private SessionManager sessionManager;

	@Autowired
	protected OpenviduConfig openviduConfig;

	@Autowired
	private KurentoClientProvider kcProvider;

	protected Map<String, Recording> startingRecordings = new ConcurrentHashMap<>();
	protected Map<String, Recording> startedRecordings = new ConcurrentHashMap<>();
	protected Map<String, Recording> sessionsRecordings = new ConcurrentHashMap<>();
	private final Map<String, ScheduledFuture<?>> automaticRecordingStopThreads = new ConcurrentHashMap<>();

	private ScheduledThreadPoolExecutor automaticRecordingStopExecutor = new ScheduledThreadPoolExecutor(
			Runtime.getRuntime().availableProcessors());

	static final String RECORDING_ENTITY_FILE = ".recording.";
	static final String IMAGE_NAME = "openvidu/openvidu-recording";
	static String IMAGE_TAG;

	private static final List<String> LAST_PARTICIPANT_LEFT_REASONS = Arrays.asList(
			new String[] { "disconnect", "forceDisconnectByUser", "forceDisconnectByServer", "networkDisconnect" });

	public SessionEventsHandler getSessionEventsHandler() {
		return this.sessionHandler;
	}

	public void initializeRecordingManager() throws OpenViduException {

		RecordingManager.IMAGE_TAG = openviduConfig.getOpenViduRecordingVersion();

		this.composedRecordingService = new ComposedRecordingService(this, openviduConfig);
		this.singleStreamRecordingService = new SingleStreamRecordingService(this, openviduConfig);

		log.info("Recording module required: Downloading openvidu/openvidu-recording:"
				+ openviduConfig.getOpenViduRecordingVersion() + " Docker image (800 MB aprox)");

		boolean imageExists = false;
		try {
			imageExists = this.recordingImageExistsLocally();
		} catch (ProcessingException exception) {
			String message = "Exception connecting to Docker daemon: ";
			if ("docker".equals(openviduConfig.getSpringProfile())) {
				final String NEW_LINE = System.getProperty("line.separator");
				message += "make sure you include the following flags in your \"docker run\" command:" + NEW_LINE
						+ "    -e openvidu.recording.path=/YOUR/PATH/TO/VIDEO/FILES" + NEW_LINE
						+ "    -e MY_UID=$(id -u $USER)" + NEW_LINE + "    -v /var/run/docker.sock:/var/run/docker.sock"
						+ NEW_LINE + "    -v /YOUR/PATH/TO/VIDEO/FILES:/YOUR/PATH/TO/VIDEO/FILES" + NEW_LINE;
			} else {
				message += "you need Docker CE installed in this machine to enable OpenVidu recording service. "
						+ "If Docker CE is already installed, make sure to add OpenVidu Server user to "
						+ "\"docker\" group: " + System.lineSeparator() + "   1) $ sudo usermod -aG docker $USER"
						+ System.lineSeparator()
						+ "   2) Log out and log back to the host to reevaluate group membership";
			}
			log.error(message);
			throw new OpenViduException(Code.RECORDING_ENABLED_BUT_DOCKER_NOT_FOUND, message);
		}

		if (imageExists) {
			log.info("Docker image already exists locally");
		} else {
			Thread t = new Thread(() -> {
				boolean keep = true;
				log.info("Downloading ");
				while (keep) {
					System.out.print(".");
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e) {
						keep = false;
						log.info("\nDownload complete");
					}
				}
			});
			t.start();
			this.downloadRecordingImage();
			t.interrupt();
			try {
				t.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			log.info("Docker image available");
		}

		// Clean any stranded openvidu/openvidu-recording container on startup
		this.removeExistingRecordingContainers();

		this.initRecordingPath();
	}

	public Recording startRecording(Session session, RecordingProperties properties) throws OpenViduException {
		Recording recording = null;
		try {
			switch (properties.outputMode()) {
			case COMPOSED:
				recording = this.composedRecordingService.startRecording(session, properties);
				break;
			case INDIVIDUAL:
				recording = this.singleStreamRecordingService.startRecording(session, properties);
				break;
			}
		} catch (OpenViduException e) {
			throw e;
		}
		if (session.getActivePublishers() == 0) {
			// Init automatic recording stop if there are now publishers when starting
			// recording
			log.info("No publisher in session {}. Starting {} seconds countdown for stopping recording",
					session.getSessionId(), this.openviduConfig.getOpenviduRecordingAutostopTimeout());
			this.initAutomaticRecordingStopThread(session);
		}
		return recording;
	}

	public Recording stopRecording(Session session, String recordingId, String reason) {
		Recording recording;
		if (session == null) {
			recording = this.startedRecordings.get(recordingId);
		} else {
			recording = this.sessionsRecordings.get(session.getSessionId());
		}
		switch (recording.getOutputMode()) {
		case COMPOSED:
			recording = this.composedRecordingService.stopRecording(session, recording, reason);
			break;
		case INDIVIDUAL:
			recording = this.singleStreamRecordingService.stopRecording(session, recording, reason);
			break;
		}
		this.abortAutomaticRecordingStopThread(session);
		return recording;
	}

	public void startOneIndividualStreamRecording(Session session, String recordingId, MediaProfileSpecType profile,
			Participant participant) {
		Recording recording = this.sessionsRecordings.get(session.getSessionId());
		if (recording == null) {
			log.error("Cannot start recording of new stream {}. Session {} is not being recorded",
					participant.getPublisherStreamId(), session.getSessionId());
		}
		if (io.openvidu.java.client.Recording.OutputMode.INDIVIDUAL.equals(recording.getOutputMode())) {
			// Start new RecorderEndpoint for this stream
			log.info("Starting new RecorderEndpoint in session {} for new stream of participant {}",
					session.getSessionId(), participant.getParticipantPublicId());
			final CountDownLatch startedCountDown = new CountDownLatch(1);
			this.singleStreamRecordingService.startRecorderEndpointForPublisherEndpoint(session, recordingId, profile,
					participant, startedCountDown);
		} else if (io.openvidu.java.client.Recording.OutputMode.COMPOSED.equals(recording.getOutputMode())
				&& !recording.hasVideo()) {
			// Connect this stream to existing Composite recorder
			log.info("Joining PublisherEndpoint to existing Composite in session {} for new stream of participant {}",
					session.getSessionId(), participant.getParticipantPublicId());
			this.composedRecordingService.joinPublisherEndpointToComposite(session, recordingId, participant);
		}
	}

	public void stopOneIndividualStreamRecording(String sessionId, String streamId) {
		Recording recording = this.sessionsRecordings.get(sessionId);
		if (recording == null) {
			log.error("Cannot stop recording of existing stream {}. Session {} is not being recorded", streamId,
					sessionId);
		}
		if (io.openvidu.java.client.Recording.OutputMode.INDIVIDUAL.equals(recording.getOutputMode())) {
			// Stop specific RecorderEndpoint for this stream
			log.info("Stopping RecorderEndpoint in session {} for stream of participant {}", sessionId, streamId);
			final CountDownLatch stoppedCountDown = new CountDownLatch(1);
			this.singleStreamRecordingService.stopRecorderEndpointOfPublisherEndpoint(sessionId, streamId,
					stoppedCountDown);
			try {
				if (!stoppedCountDown.await(5, TimeUnit.SECONDS)) {
					log.error("Error waiting for recorder endpoint of stream {} to stop in session {}", streamId,
							sessionId);
				}
			} catch (InterruptedException e) {
				log.error("Exception while waiting for state change", e);
			}
		} else if (io.openvidu.java.client.Recording.OutputMode.COMPOSED.equals(recording.getOutputMode())
				&& !recording.hasVideo()) {
			// Disconnect this stream from existing Composite recorder
			log.info("Removing PublisherEndpoint from Composite in session {} for stream of participant {}", sessionId,
					streamId);
			this.composedRecordingService.removePublisherEndpointFromComposite(sessionId, streamId);
		}
	}

	public boolean sessionIsBeingRecorded(String sessionId) {
		return (this.sessionsRecordings.get(sessionId) != null);
	}

	public Recording getStartedRecording(String recordingId) {
		return this.startedRecordings.get(recordingId);
	}

	public Recording getStartingRecording(String recordingId) {
		return this.startingRecordings.get(recordingId);
	}

	public Collection<Recording> getFinishedRecordings() {
		return this.getAllRecordingsFromHost().stream()
				.filter(recording -> (recording.getStatus().equals(io.openvidu.java.client.Recording.Status.stopped)
						|| recording.getStatus().equals(io.openvidu.java.client.Recording.Status.available)))
				.collect(Collectors.toSet());
	}

	public Recording getRecording(String recordingId) {
		return this.getRecordingFromHost(recordingId);
	}

	public Collection<Recording> getAllRecordings() {
		return this.getAllRecordingsFromHost();
	}

	public String getFreeRecordingId(String sessionId, String shortSessionId) {
		Set<String> recordingIds = this.getRecordingIdsFromHost();
		String recordingId = shortSessionId;
		boolean isPresent = recordingIds.contains(recordingId);
		int i = 1;

		while (isPresent) {
			recordingId = shortSessionId + "-" + i;
			i++;
			isPresent = recordingIds.contains(recordingId);
		}

		return recordingId;
	}

	public HttpStatus deleteRecordingFromHost(String recordingId, boolean force) {

		if (!force && (this.startedRecordings.containsKey(recordingId)
				|| this.startingRecordings.containsKey(recordingId))) {
			// Cannot delete an active recording
			return HttpStatus.CONFLICT;
		}

		Recording recording = getRecordingFromHost(recordingId);
		if (recording == null) {
			return HttpStatus.NOT_FOUND;
		}

		File folder = new File(this.openviduConfig.getOpenViduRecordingPath());
		File[] files = folder.listFiles();
		for (int i = 0; i < files.length; i++) {
			if (files[i].isDirectory() && files[i].getName().equals(recordingId)) {
				// Correct folder. Delete it
				try {
					FileUtils.deleteDirectory(files[i]);
				} catch (IOException e) {
					log.error("Couldn't delete folder {}", files[i].getAbsolutePath());
				}
				break;
			}
		}

		return HttpStatus.NO_CONTENT;
	}

	public Recording getRecordingFromEntityFile(File file) {
		if (file.isFile() && file.getName().startsWith(RecordingManager.RECORDING_ENTITY_FILE)) {
			JsonObject json = null;
			try {
				json = new JsonParser().parse(new FileReader(file)).getAsJsonObject();
			} catch (IOException e) {
				return null;
			}
			return new Recording(json);
		}
		return null;
	}

	public void initAutomaticRecordingStopThread(final Session session) {
		final String recordingId = this.sessionsRecordings.get(session.getSessionId()).getId();
		ScheduledFuture<?> future = this.automaticRecordingStopExecutor.schedule(() -> {

			log.info("Stopping recording {} after {} seconds wait (no publisher published before timeout)", recordingId,
					this.openviduConfig.getOpenviduRecordingAutostopTimeout());

			if (this.automaticRecordingStopThreads.remove(session.getSessionId()) != null) {
				if (session.getParticipants().size() == 0 || (session.getParticipants().size() == 1
						&& session.getParticipantByPublicId(ProtocolElements.RECORDER_PARTICIPANT_PUBLICID) != null)) {
					// Close session if there are no participants connected (except for RECORDER).
					// This code won't be executed only when some user reconnects to the session
					// but never publishing (publishers automatically abort this thread)
					log.info("Closing session {} after automatic stop of recording {}", session.getSessionId(),
							recordingId);
					sessionManager.closeSessionAndEmptyCollections(session, "automaticStop");
					sessionManager.showTokens();
				} else {
					this.stopRecording(null, recordingId, "automaticStop");
				}
			} else {
				// This code is reachable if there already was an automatic stop of a recording
				// caused by not user publishing within timeout after recording started, and a
				// new automatic stop thread was started by last user leaving the session
				log.warn("Recording {} was already automatically stopped by a previous thread", recordingId);
			}

		}, this.openviduConfig.getOpenviduRecordingAutostopTimeout(), TimeUnit.SECONDS);
		this.automaticRecordingStopThreads.putIfAbsent(session.getSessionId(), future);
	}

	public boolean abortAutomaticRecordingStopThread(Session session) {
		ScheduledFuture<?> future = this.automaticRecordingStopThreads.remove(session.getSessionId());
		if (future != null) {
			boolean cancelled = future.cancel(false);
			if (session.getParticipants().size() == 0 || (session.getParticipants().size() == 1
					&& session.getParticipantByPublicId(ProtocolElements.RECORDER_PARTICIPANT_PUBLICID) != null)) {
				// Close session if there are no participants connected (except for RECORDER).
				// This code will only be executed if recording is manually stopped during the
				// automatic stop timeout, so the session must be also closed
				log.info(
						"Ongoing recording of session {} was explicetly stopped within timeout for automatic recording stop. Closing session",
						session.getSessionId());
				sessionManager.closeSessionAndEmptyCollections(session, "automaticStop");
				sessionManager.showTokens();
			}
			return cancelled;
		} else {
			return true;
		}
	}

	public Recording updateRecordingUrl(Recording recording) {
		if (openviduConfig.getOpenViduRecordingPublicAccess()) {
			if (io.openvidu.java.client.Recording.Status.stopped.equals(recording.getStatus())) {

				String extension;
				switch (recording.getOutputMode()) {
				case COMPOSED:
					extension = recording.hasVideo() ? "mp4" : "webm";
					break;
				case INDIVIDUAL:
					extension = "zip";
					break;
				default:
					extension = "mp4";
				}

				recording.setUrl(this.openviduConfig.getFinalUrl() + "recordings/" + recording.getId() + "/"
						+ recording.getName() + "." + extension);
				recording.setStatus(io.openvidu.java.client.Recording.Status.available);
			}
		}
		return recording;
	}

	private void removeExistingRecordingContainers() {
		List<Container> existingContainers = this.composedRecordingService.dockerClient.listContainersCmd()
				.withShowAll(true).exec();
		for (Container container : existingContainers) {
			if (container.getImage().startsWith(RecordingManager.IMAGE_NAME)) {
				log.info("Stranded openvidu/openvidu-recording Docker container ({}) removed on startup",
						container.getId());
				this.composedRecordingService.dockerClient.removeContainerCmd(container.getId()).withForce(true).exec();
			}
		}
	}

	private boolean recordingImageExistsLocally() {
		boolean imageExists = false;
		try {
			this.composedRecordingService.dockerClient.inspectImageCmd(IMAGE_NAME + ":" + IMAGE_TAG).exec();
			imageExists = true;
		} catch (NotFoundException nfe) {
			imageExists = false;
		} catch (ProcessingException e) {
			throw e;
		}
		return imageExists;
	}

	private void downloadRecordingImage() {
		try {
			this.composedRecordingService.dockerClient.pullImageCmd(IMAGE_NAME + ":" + IMAGE_TAG)
					.exec(new PullImageResultCallback()).awaitSuccess();
		} catch (NotFoundException | InternalServerErrorException e) {
			if (recordingImageExistsLocally()) {
				log.info("Docker image '{}' exists locally", IMAGE_NAME + ":" + IMAGE_TAG);
			} else {
				throw e;
			}
		} catch (DockerClientException e) {
			log.info("Error on Pulling '{}' image. Probably because the user has stopped the execution",
					IMAGE_NAME + ":" + IMAGE_TAG);
			throw e;
		}
	}

	private Recording getRecordingFromHost(String recordingId) {
		log.info(this.openviduConfig.getOpenViduRecordingPath() + recordingId + "/"
				+ RecordingManager.RECORDING_ENTITY_FILE + recordingId);
		File file = new File(this.openviduConfig.getOpenViduRecordingPath() + recordingId + "/"
				+ RecordingManager.RECORDING_ENTITY_FILE + recordingId);
		log.info("File exists: " + file.exists());
		Recording recording = this.getRecordingFromEntityFile(file);
		if (recording != null) {
			this.updateRecordingUrl(recording);
		}
		return recording;
	}

	private Set<Recording> getAllRecordingsFromHost() {
		File folder = new File(this.openviduConfig.getOpenViduRecordingPath());
		File[] files = folder.listFiles();

		Set<Recording> recordingEntities = new HashSet<>();
		for (int i = 0; i < files.length; i++) {
			if (files[i].isDirectory()) {
				File[] innerFiles = files[i].listFiles();
				for (int j = 0; j < innerFiles.length; j++) {
					Recording recording = this.getRecordingFromEntityFile(innerFiles[j]);
					if (recording != null) {
						this.updateRecordingUrl(recording);
						recordingEntities.add(recording);
					}
				}
			}
		}
		return recordingEntities;
	}

	private Set<String> getRecordingIdsFromHost() {
		File folder = new File(this.openviduConfig.getOpenViduRecordingPath());
		File[] files = folder.listFiles();

		Set<String> fileNamesNoExtension = new HashSet<>();
		for (int i = 0; i < files.length; i++) {
			if (files[i].isDirectory()) {
				File[] innerFiles = files[i].listFiles();
				for (int j = 0; j < innerFiles.length; j++) {
					if (innerFiles[j].isFile()
							&& innerFiles[j].getName().startsWith(RecordingManager.RECORDING_ENTITY_FILE)) {
						fileNamesNoExtension
								.add(innerFiles[j].getName().replaceFirst(RecordingManager.RECORDING_ENTITY_FILE, ""));
						break;
					}
				}
			}
		}
		return fileNamesNoExtension;
	}

	private void initRecordingPath() throws OpenViduException {
		log.info("Initializing recording path");

		final String recordingPathString = this.openviduConfig.getOpenViduRecordingPath();
		final String testFolderPath = recordingPathString + "/TEST_RECORDING_PATH_" + System.currentTimeMillis();
		final String testFilePath = testFolderPath + "/TEST_RECORDING_PATH.webm";

		Path recordingPath = null;
		try {
			recordingPath = Files.createDirectories(Paths.get(recordingPathString));
		} catch (IOException e) {
			String errorMessage = "The recording path \"" + recordingPathString
					+ "\" is not valid. Reason: OpenVidu Server cannot find path \"" + recordingPathString
					+ "\" and doesn't have permissions to create it";
			log.error(errorMessage);
			throw new OpenViduException(Code.RECORDING_PATH_NOT_VALID, errorMessage);
		}

		// Check OpenVidu Server write permissions in recording path
		if (!Files.isWritable(recordingPath)) {
			String errorMessage = "The recording path \"" + recordingPathString
					+ "\" is not valid. Reason: OpenVidu Server needs write permissions. Try running command \"sudo chmod 777 "
					+ recordingPathString + "\"";
			log.error(errorMessage);
			throw new OpenViduException(Code.RECORDING_PATH_NOT_VALID, errorMessage);
		} else {
			log.info("OpenVidu Server has write permissions on recording path: {}", recordingPathString);
		}

		if (openviduConfig.openviduRecordingCustomLayoutChanged()) {
			// Property openvidu.recording.custom-layout changed
			File dir = new File(openviduConfig.getOpenviduRecordingCustomLayout());
			if (dir.exists()) {
				if (dir.listFiles() == null) {
					String errorMessage = "The custom layouts path \""
							+ openviduConfig.getOpenviduRecordingCustomLayout()
							+ "\" is not valid. Reason: OpenVidu Server needs read permissions. Try running command \"sudo chmod 755 "
							+ openviduConfig.getOpenviduRecordingCustomLayout() + "\"";
					log.error(errorMessage);
					throw new OpenViduException(Code.RECORDING_PATH_NOT_VALID, errorMessage);
				} else {
					log.info("OpenVidu Server has read permissions on custom layout path: {}",
							openviduConfig.getOpenviduRecordingCustomLayout());
				}
			} else {
				String errorMessage = "The custom layouts path \"" + recordingPathString
						+ "\" is not valid. Reason: OpenVidu Server cannot find path \"" + recordingPathString + "\"";
				log.error(errorMessage);
				throw new OpenViduException(Code.RECORDING_PATH_NOT_VALID, errorMessage);
			}
		}

		// Check Kurento Media Server write permissions in recording path
		KurentoClientSessionInfo kcSessionInfo = new OpenViduKurentoClientSessionInfo("TEST_RECORDING_PATH",
				"TEST_RECORDING_PATH");
		MediaPipeline pipeline = this.kcProvider.getKurentoClient(kcSessionInfo).createMediaPipeline();
		RecorderEndpoint recorder = new RecorderEndpoint.Builder(pipeline, "file://" + testFilePath).build();

		recorder.addErrorListener(new EventListener<ErrorEvent>() {
			@Override
			public void onEvent(ErrorEvent event) {
				if (event.getErrorCode() == 6) {
					// KMS write permissions error
					log.error("The recording path \"" + recordingPathString
							+ "\" is not valid. Reason: Kurento Media Server needs write permissions. Try running command \"sudo chmod 777 "
							+ recordingPathString + "\"");
					log.error(
							"Error initializing recording path \"{}\" set with system property \"openvidu.recording.path\". Shutting down OpenVidu Server",
							recordingPathString);
					System.exit(1);
				}
			}
		});

		recorder.record();

		try {
			Thread.sleep(250);
		} catch (InterruptedException e1) {
			e1.printStackTrace();
		}

		recorder.release();
		pipeline.release();

		log.info("Kurento Media Server has write permissions on recording path: {}", recordingPathString);

		try {
			new CustomFileManager().deleteFolder(testFolderPath);
			log.info("OpenVidu Server has write permissions over files created by Kurento Media Server");
		} catch (IOException e) {
			String errorMessage = "The recording path \"" + recordingPathString
					+ "\" is not valid. Reason: OpenVidu Server does not have write permissions over files created by Kurento Media Server. "
					+ "Try running Kurento Media Server as user \"" + System.getProperty("user.name")
					+ "\" or run OpenVidu Server as superuser";
			log.error(errorMessage);
			log.error("Be aware that a folder \"{}\" was created and should be manually deleted (\"sudo rm -rf {}\")",
					testFolderPath, testFolderPath);
			throw new OpenViduException(Code.RECORDING_PATH_NOT_VALID, errorMessage);
		}

		log.info("Recording path successfully initialized at {}", this.openviduConfig.getOpenViduRecordingPath());
	}

	public static String finalReason(String reason) {
		if (RecordingManager.LAST_PARTICIPANT_LEFT_REASONS.contains(reason)) {
			return "lastParticipantLeft";
		} else {
			return reason;
		}
	}

}
