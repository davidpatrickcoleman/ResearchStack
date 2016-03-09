package org.researchstack.bridge;

import android.content.Context;
import android.content.Intent;
import android.support.annotation.NonNull;
import android.util.Log;

import com.google.gson.Gson;

import org.apache.commons.lang3.StringUtils;
import org.researchstack.backbone.StorageAccess;
import org.researchstack.backbone.helpers.LogExt;
import org.researchstack.backbone.result.StepResult;
import org.researchstack.backbone.result.TaskResult;
import org.researchstack.backbone.storage.NotificationHelper;
import org.researchstack.backbone.storage.database.AppDatabase;
import org.researchstack.backbone.storage.database.TaskNotification;
import org.researchstack.backbone.storage.file.FileAccessException;
import org.researchstack.backbone.utils.FormatHelper;
import org.researchstack.backbone.utils.ObservableUtils;
import org.researchstack.bridge.body.ConsentSignatureBody;
import org.researchstack.bridge.body.EmailBody;
import org.researchstack.bridge.body.SharingOptionBody;
import org.researchstack.bridge.body.SignInBody;
import org.researchstack.bridge.body.SignUpBody;
import org.researchstack.bridge.body.SurveyAnswer;
import org.researchstack.bridge.body.SurveyResponse;
import org.researchstack.bridge.body.WithdrawalBody;
import org.researchstack.skin.AppPrefs;
import org.researchstack.skin.DataProvider;
import org.researchstack.skin.DataResponse;
import org.researchstack.skin.model.SchedulesAndTasksModel;
import org.researchstack.skin.model.TaskModel;
import org.researchstack.skin.model.User;
import org.researchstack.skin.notification.TaskAlertReceiver;
import org.researchstack.skin.schedule.ScheduleHelper;
import org.researchstack.skin.task.SmartSurveyTask;
import org.researchstack.skin.utils.JsonUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.GsonConverterFactory;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.RxJavaCallAdapterFactory;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Path;
import rx.Observable;
import rx.schedulers.Schedulers;

/*
* This is a very simple implementation that hits only part of the Sage Bridge REST API
* TODO a complete port of the Sage Bridge Java SDK for android: https://github.com/Sage-Bionetworks/BridgeJavaSDK
 */
public abstract class BridgeDataProvider extends DataProvider
{
    public static final String TEMP_CONSENT_JSON_FILE_NAME = "/consent_sig";
    public static final String USER_SESSION_PATH           = "/user_session";
    public static final String USER_PATH                   = "/user";

    //TODO Add build flavors, add var to BuildConfig for STUDY_ID
    public static final  String STUDY_ID = "ohsu-molemapper";
    private static final String CLIENT   = "android";

    //TODO Add build flavors, add var to BuildConfig for BASE_URL
    String BASE_URL = "https://webservices-staging.sagebridge.org/v3/";

    // initial data upload
    public static final String INITIAL_DATA_FILENAME  = "initialData.json";
    public static final String INITIAL_DATA_ITEM_NAME = "initialData";

    private   BridgeService   service;
    protected UserSessionInfo userSessionInfo;
    protected Gson    gson     = new Gson();
    protected boolean signedIn = false;

    // TODO figure out if there's a better way to do this
    // these are used to get task/step guids without rereading the json files and iterating through
    private Map<String, TaskModel> loadedTasks     = new HashMap<>();
    private Map<String, String>    loadedTaskGuids = new HashMap<>();

    protected abstract int getPublicKeyResId();

    public BridgeDataProvider()
    {
        buildRetrofitService(null);
    }

    private void buildRetrofitService(UserSessionInfo userSessionInfo)
    {
        HttpLoggingInterceptor interceptor = new HttpLoggingInterceptor(message -> LogExt.i(getClass(),
                message));
        interceptor.setLevel(HttpLoggingInterceptor.Level.BODY);

        final String sessionToken;
        if(userSessionInfo != null)
        {
            sessionToken = userSessionInfo.getSessionToken();
        }
        else
        {
            sessionToken = "";
        }

        Interceptor headerInterceptor = chain -> {
            Request original = chain.request();

            //TODO Get proper app-name and version name
            Request request = original.newBuilder()
                    .header("User-Agent", " Mole Mapper/1")
                    .header("Bridge-Session", sessionToken)
                    .method(original.method(), original.body())
                    .build();

            return chain.proceed(request);
        };

        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(headerInterceptor)
                .addInterceptor(interceptor)
                .build();

        Retrofit retrofit = new Retrofit.Builder().addCallAdapterFactory(RxJavaCallAdapterFactory.create())
                .addConverterFactory(GsonConverterFactory.create())
                .baseUrl(BASE_URL)
                .client(client)
                .build();
        service = retrofit.create(BridgeService.class);
    }

    @Override
    public Observable<DataResponse> initialize(Context context)
    {
        return Observable.create(subscriber -> {
            userSessionInfo = loadUserSession(context);
            signedIn = userSessionInfo != null;

            buildRetrofitService(userSessionInfo);
            subscriber.onNext(new DataResponse(true, null));
            checkForTempConsentAndUpload(context);
        });
    }

    private void checkForTempConsentAndUpload(Context context)
    {
        // If we are signed in, not consented on the server, but consented locally, upload consent
        if(isSignedIn(context) && ! userSessionInfo.isConsented() && StorageAccess.getInstance()
                .getFileAccess()
                .dataExists(context, TEMP_CONSENT_JSON_FILE_NAME))
        {
            try
            {
                ConsentSignatureBody consent = loadConsentSignatureBody(context);
                uploadConsent(context, consent);
            }
            catch(Exception e)
            {
                throw new RuntimeException("Error loading consent", e);
            }
        }
    }

    /**
     * @param context
     * @return true if we are consented
     */
    @Override
    public boolean isConsented(Context context)
    {
        return userSessionInfo.isConsented() || StorageAccess.getInstance()
                .getFileAccess()
                .dataExists(context, TEMP_CONSENT_JSON_FILE_NAME);
    }

    @Override
    public Observable<DataResponse> withdrawConsent(Context context, String reason)
    {
        return service.withdrawConsent(new WithdrawalBody(reason)).doOnNext(response -> {
            if(response.isSuccess())
            {
                userSessionInfo.setConsented(false);
                saveUserSession(context, userSessionInfo);
                buildRetrofitService(userSessionInfo);
            }
        }).map(response -> {
            boolean success = response.isSuccess();
            return new DataResponse(success, response.message());
        });
    }

    @Override
    public Observable<DataResponse> signUp(Context context, String email, String username, String password)
    {
        //TODO pass in data groups, remove roles
        SignUpBody body = new SignUpBody(STUDY_ID, email, username, password, null, null);

        // TODO Saving email to user object should exist elsewhere.
        // Save email to user object.
        User user = loadUser(context);
        if(user == null)
        {
            user = new User();
        }
        user.setEmail(email);
        saveUser(context, user);

        return service.signUp(body).map(message -> {
            DataResponse response = new DataResponse();
            response.setSuccess(true);
            return response;
        });
    }

    @Override
    public Observable<DataResponse> signIn(Context context, String username, String password)
    {
        SignInBody body = new SignInBody(STUDY_ID, username, password);

        // response 412 still has a response body, so catch all http errors here
        return service.signIn(body).doOnNext(response -> {

            if(response.code() == 200)
            {
                userSessionInfo = response.body();
            }
            else if(response.code() == 412)
            {
                try
                {
                    String errorBody = response.errorBody().string();
                    userSessionInfo = gson.fromJson(errorBody, UserSessionInfo.class);
                }
                catch(IOException e)
                {
                    throw new RuntimeException("Error deserializing server sign in response");
                }

            }

            if(userSessionInfo != null)
            {
                // TODO if we are direct from signing in, we need to load the user profile object
                // from the server.
                saveUserSession(context, userSessionInfo);
                buildRetrofitService(userSessionInfo);
                checkForTempConsentAndUpload(context);
            }
        }).map(response -> {
            boolean success = response.isSuccess() || response.code() == 412;
            return new DataResponse(success, response.message());
        });
    }

    @Override
    public Observable<DataResponse> signOut(Context context)
    {
        return service.signOut().map(response -> new DataResponse(response.isSuccess(), null));
    }

    @Override
    public Observable<DataResponse> resendEmailVerification(Context context, String email)
    {
        EmailBody body = new EmailBody(STUDY_ID, email);
        return service.resendEmailVerification(body);
    }

    @Override
    public boolean isSignedUp(Context context)
    {
        User user = loadUser(context);
        return user != null && user.getEmail() != null;
    }

    @Override
    public boolean isSignedIn(Context context)
    {
        return signedIn;
    }

    @Override
    public void saveConsent(Context context, String name, Date birthDate, String imageData, String signatureDate, String scope)
    {
        // User is not signed in yet, so we need to save consent info to disk for later upload
        ConsentSignatureBody signature = new ConsentSignatureBody(STUDY_ID,
                name,
                birthDate,
                imageData,
                "image/png",
                scope);
        writeJsonString(context, gson.toJson(signature), TEMP_CONSENT_JSON_FILE_NAME);

        // TODO init here isnt great. Refactor and create saveUser method.
        User user = loadUser(context);
        if(user == null)
        {
            user = new User();
        }
        user.setName(signature.name);
        user.setBirthDate(signature.birthdate);
        saveUser(context, user);
    }

    @Override
    public User getUser(Context context)
    {
        return loadUser(context);
    }

    @Override
    public String getUserSharingScope(Context context)
    {
        return userSessionInfo.getSharingScope();
    }

    @Override
    public void setUserSharingScope(Context context, String scope)
    {
        // Update scope on server
        service.dataSharing(new SharingOptionBody(scope))
                .compose(ObservableUtils.applyDefault())
                .doOnNext(response -> {
                    if(response.isSuccess())
                    {
                        userSessionInfo.setSharingScope(scope);
                        saveUserSession(context, userSessionInfo);
                    }
                })
                .subscribe(response -> LogExt.d(getClass(),
                        "Response: " + response.code() + ", message: " +
                                response.message()), error -> {
                    LogExt.e(getClass(), error.getMessage());
                });
    }

    private ConsentSignatureBody loadConsentSignatureBody(Context context)
    {
        String consentJson = loadJsonString(context, TEMP_CONSENT_JSON_FILE_NAME);
        return gson.fromJson(consentJson, ConsentSignatureBody.class);
    }

    private void uploadConsent(Context context, ConsentSignatureBody consent)
    {
        service.consentSignature(consent)
                .compose(ObservableUtils.applyDefault())
                .subscribe(response -> {
                    // TODO this isn't good, we should be getting an updated user session info from
                    // TODO the server, but there doesn't seem to be a way to do that without
                    // TODO signing in again with the username and password
                    if(response.code() == 201 ||
                            response.code() == 409) // success or already consented
                    {
                        userSessionInfo.setConsented(true);
                        saveUserSession(context, userSessionInfo);

                        LogExt.d(getClass(), "Response: " + response.code() + ", message: " +
                                response.message());

                        StorageAccess.getInstance()
                                .getFileAccess()
                                .clearData(context, TEMP_CONSENT_JSON_FILE_NAME);
                    }
                    else
                    {
                        throw new RuntimeException(
                                "Error uploading consent, code: " + response.code() + " message: " +
                                        response.message());
                    }
                });
    }

    @Override
    public String getUserEmail(Context context)
    {
        User user = loadUser(context);
        return user == null ? null : user.getEmail();
    }

    private void saveUserSession(Context context, UserSessionInfo userInfo)
    {
        String userSessionJson = gson.toJson(userInfo);
        writeJsonString(context, userSessionJson, USER_SESSION_PATH);
    }

    private User loadUser(Context context)
    {
        try
        {
            String user = loadJsonString(context, USER_PATH);
            return gson.fromJson(user, User.class);
        }
        catch(FileAccessException e)
        {
            return null;
        }
    }

    private void saveUser(Context context, User profile)
    {
        writeJsonString(context, gson.toJson(profile), USER_PATH);
    }

    private void writeJsonString(Context context, String userSessionJson, String userSessionPath)
    {
        StorageAccess.getInstance()
                .getFileAccess()
                .writeData(context, userSessionPath, userSessionJson.getBytes());
    }

    private UserSessionInfo loadUserSession(Context context)
    {
        try
        {
            String userSessionJson = loadJsonString(context, USER_SESSION_PATH);
            return gson.fromJson(userSessionJson, UserSessionInfo.class);
        }
        catch(FileAccessException e)
        {
            return null;
        }
    }

    private String loadJsonString(Context context, String path)
    {
        return new String(StorageAccess.getInstance().getFileAccess().readData(context, path));
    }

    @Override
    public List<SchedulesAndTasksModel.TaskModel> loadTasksAndSchedules(Context context)
    {
        SchedulesAndTasksModel schedulesAndTasksModel = JsonUtils.loadClass(context,
                SchedulesAndTasksModel.class,
                "tasks_and_schedules");

        AppDatabase db = StorageAccess.getInstance().getAppDatabase();

        ArrayList<SchedulesAndTasksModel.TaskModel> tasks = new ArrayList<>();
        for(SchedulesAndTasksModel.ScheduleModel schedule : schedulesAndTasksModel.schedules)
        {
            for(SchedulesAndTasksModel.TaskModel task : schedule.tasks)
            {
                if(task.taskFileName == null)
                {
                    LogExt.e(getClass(), "No filename found for task with id: " + task.taskID);
                    continue;
                }

                // TODO loading the task json here is bad, but the GUID is in the schedule
                // TODO json but the id is in the task json
                TaskModel taskModel = JsonUtils.loadClass(context,
                        TaskModel.class,
                        task.taskFileName);
                TaskResult result = db.loadLatestTaskResult(taskModel.identifier);

                if(result == null)
                {
                    tasks.add(task);
                }
                else if(StringUtils.isNotEmpty(schedule.scheduleString))
                {
                    Date date = ScheduleHelper.nextSchedule(schedule.scheduleString,
                            result.getEndDate());
                    if(date.before(new Date()))
                    {
                        tasks.add(task);
                    }
                }
            }
        }
        return tasks;
    }

    @Override
    public SmartSurveyTask loadTask(Context context, SchedulesAndTasksModel.TaskModel task)
    {
        // TODO 2 types of taskmodels here, confusing
        TaskModel taskModel = JsonUtils.loadClass(context, TaskModel.class, task.taskFileName);
        loadedTasks.put(taskModel.identifier, taskModel);
        loadedTaskGuids.put(taskModel.identifier, task.taskID);
        return new SmartSurveyTask(taskModel);
    }

    @Override
    public void uploadTaskResult(Context context, TaskResult taskResult)
    {
        // TODO should we do this here?
        // Add to database
        StorageAccess.getInstance().getAppDatabase().saveTaskResult(taskResult);

        // Update/Create TaskNotificationService
        if(AppPrefs.getInstance(context).isTaskReminderEnabled())
        {
            Log.i("SampleDataProvider", "uploadTaskResult() _ isTaskReminderEnabled() = true");

            String chronTime = findChronTime(context, taskResult.getIdentifier());

            // If chronTime is null then either the task is not repeating OR its not found within
            // the task_and_schedules.xml
            if(chronTime != null)
            {
                scheduleReminderNotification(context, taskResult.getEndDate(), chronTime);
            }
        }

        // Upload using the world-wide-web
        // TODO use UploadResult queue for this? (upload encrypted)
        TaskModel taskModel = loadedTasks.get(taskResult.getIdentifier());
        List<TaskModel.StepModel> elements = taskModel.elements;
        Map<String, TaskModel.StepModel> stepModels = new HashMap<>(elements.size());

        for(TaskModel.StepModel stepModel : elements)
        {
            stepModels.put(stepModel.identifier, stepModel);
        }

        ArrayList<SurveyAnswer> surveyAnswers = new ArrayList<>();

        for(StepResult stepResult : taskResult.getResults().values())
        {
            boolean declined = stepResult.getResults().size() == 0;
            List<String> answers = new ArrayList<>();
            for(Object answer : stepResult.getResults().values())
            {
                answers.add(answer.toString());
            }
            SurveyAnswer surveyAnswer = new SurveyAnswer(stepModels.get(stepResult.getIdentifier()).guid,
                    declined,
                    CLIENT,
                    stepResult.getEndDate(),
                    answers);
            surveyAnswers.add(surveyAnswer);
        }

        SurveyResponse response = new SurveyResponse(taskResult.getIdentifier(),
                taskResult.getStartDate(),
                taskResult.getEndDate(),
                loadedTaskGuids.get(taskResult.getIdentifier()),
                // TODO createdOn date for survey not in the schedule json, not sure what date to use
                FormatHelper.DEFAULT_FORMAT.format(taskResult.getStartDate()),
                SurveyResponse.Status.FINISHED,
                surveyAnswers);
        // TODO use encrypted upload?
        service.surveyResponses(response)
                .compose(ObservableUtils.applyDefault())
                .subscribe(httpResponse -> LogExt.d(getClass(),
                        "Successful upload of survey, identifier: " + httpResponse.identifier),
                        error -> {
                            LogExt.e(getClass(), "Error uploading survey");
                            error.printStackTrace();
                        });
    }

    // TODO this stinks, I should be able to query the DB and find the chrono time.
    private String findChronTime(Context context, String identifier)
    {
        SchedulesAndTasksModel schedulesAndTasksModel = JsonUtils.loadClass(context,
                SchedulesAndTasksModel.class,
                "tasks_and_schedules");

        for(SchedulesAndTasksModel.ScheduleModel schedule : schedulesAndTasksModel.schedules)
        {
            for(SchedulesAndTasksModel.TaskModel task : schedule.tasks)
            {
                // TODO loading the task json here is bad, but the GUID is in the schedule
                // TODO json but the id is in the task json
                TaskModel taskModel = JsonUtils.loadClass(context,
                        TaskModel.class,
                        task.taskFileName);

                if(taskModel.identifier.equals(identifier))
                {
                    return schedule.scheduleString;
                }
            }
        }
        return null;
    }

    private void scheduleReminderNotification(Context context, Date endDate, String chronTime)
    {
        Log.i("SampleDataProvider", "scheduleReminderNotification()");

        // Save TaskNotification to DB
        TaskNotification notification = new TaskNotification();
        notification.endDate = endDate;
        notification.chronTime = chronTime;
        NotificationHelper.getInstance(context).saveTaskNotification(notification);

        // Add notification to Alarm Manager
        Intent intent = new Intent(TaskAlertReceiver.ALERT_CREATE);
        intent.putExtra(TaskAlertReceiver.KEY_NOTIFICATION, notification);
        context.sendBroadcast(intent);
    }

    @Override
    public abstract void processInitialTaskResult(Context context, TaskResult taskResult);

    public void uploadPendingFiles(Context context)
    {
        List<UploadRequest> uploadRequests = ((UploadQueue) StorageAccess.getInstance()
                .getAppDatabase()).loadUploadRequests();

        for(UploadRequest uploadRequest : uploadRequests)
        {
            if(uploadRequest.bridgeId == null)
            {
                LogExt.d(getClass(), "Starting upload for request: " + uploadRequest.name);
                uploadFile(context, uploadRequest);
            }
            else
            {
                LogExt.d(getClass(),
                        "Bridge ID found, confirming upload for: " + uploadRequest.name);
                confirmUpload(context, uploadRequest);
            }
        }
    }

    private void uploadFile(Context context, UploadRequest request)
    {
        service.requestUploadSession(request)
                .flatMap(uploadSession -> uploadToS3(context, request, uploadSession))
                .flatMap(id -> {
                    LogExt.d(getClass(), "Notifying bridge of s3 upload: " + id);

                    // Updating request entry with Bridge ID for saving on success
                    request.bridgeId = id;

                    return service.uploadComplete(id);
                })
                .subscribeOn(Schedulers.io())
                .subscribe(completeResponse -> {
                    LogExt.d(getClass(), "Notified bridge of s3 upload, need to confirm");
                    // update UploadRequest in DB with id for later confirmation
                    ((UploadQueue) StorageAccess.getInstance().getAppDatabase()).saveUploadRequest(
                            request);
                }, error -> {
                    error.printStackTrace();
                    LogExt.e(getClass(), "Error uploading file to S3, will try again");
                });
    }

    @NonNull
    private Observable<String> uploadToS3(Context context, UploadRequest request, UploadSession uploadSession)
    {
        // retrofit doesn't like making requests outside of your api, use okhttp to make the call
        return Observable.create(subscriber -> {
            // Request will fail without Content-MD5, Content-Type, and Content-Length
            LogExt.d(getClass(), "Uploading to S3");
            RequestBody body = RequestBody.create(MediaType.parse(request.contentType),
                    new File(getFilesDir(context), request.name));
            Request awsRequest = new Request.Builder().url(uploadSession.url)
                    .put(body)
                    .header("Content-MD5", request.contentMd5)
                    .build();

            okhttp3.Response response = null;
            try
            {
                response = new OkHttpClient().newCall(awsRequest).execute();

                if(response.isSuccessful())
                {
                    LogExt.d(getClass(), "Successful s3 upload");
                    subscriber.onNext(uploadSession.id);
                }
                else
                {
                    throw new RuntimeException("Response unsuccessful, code: " + response.code());
                }
            }
            catch(IOException e)
            {
                throw new RuntimeException(e);
            }
        });
    }

    private void confirmUpload(Context context, UploadRequest request)
    {
        service.uploadStatus(request.bridgeId)
                .subscribeOn(Schedulers.io())
                .subscribe(uploadValidationStatus -> {
                    LogExt.d(getClass(), "Received validation status from Bridge(" +
                            uploadValidationStatus.getStatus() + ")");

                    switch(uploadValidationStatus.getStatus())
                    {
                        case UNKNOWN:
                        case VALIDATION_FAILED:
                            LogExt.e(getClass(), "Unrecoverable error, retry");
                            // TODO figure out what to actually do on unrecoverable
                            break;

                        case REQUESTED:
                            LogExt.e(getClass(),
                                    "Status is still 'requested' for some reason, will retry upload later");
                            // removing bridge id so upload is retried later
                            request.bridgeId = null;
                            ((UploadQueue) StorageAccess.getInstance()
                                    .getAppDatabase()).saveUploadRequest(request);
                            break;

                        case SUCCEEDED:
                            LogExt.d(getClass(), "Status is 'success', removing request locally");
                            deleteUploadRequest(context, request);
                            break;

                        case VALIDATION_IN_PROGRESS:
                        default:
                            LogExt.d(getClass(),
                                    "Status is pending, will retry confirmation later");
                            // No action necessary
                            break;

                    }
                }, error -> {
                    error.printStackTrace();
                    LogExt.e(getClass(), "Error connecting to Bridge server, will try again later");
                });
    }

    private void deleteUploadRequest(Context context, UploadRequest request)
    {
        ((UploadQueue) StorageAccess.getInstance().getAppDatabase()).deleteUploadRequest(request);

        File file = new File(getFilesDir(context), request.name);
        if(file.exists() && file.delete())
        {
            LogExt.d(getClass(), "Deleted file: " + file.getName());
        }
        else
        {
            LogExt.d(getClass(), "Could not delete file: " + request.name);
        }
    }

    // TODO figure out what directory to save files in and where to put this method
    public static File getFilesDir(Context context)
    {
        return context.getFilesDir();
    }

    public interface BridgeService
    {

        /**
         * @return One of the following responses
         * <ul>
         * <li><b>201</b> returns message that user has been signed up</li>
         * <li><b>473</b> error - returns message that study is full</li>
         * </ul>
         */
        @Headers("Content-Type: application/json")
        @POST("auth/signUp")
        Observable<BridgeMessageResponse> signUp(@Body SignUpBody body);

        /**
         * @return One of the following responses
         * <ul>
         * <li><b>200</b> returns UserSessionInfo Object</li>
         * <li><b>404</b> error - "Credentials incorrect or missing"</li>
         * <li><b>412</b> error - "User has not consented to research"</li>
         * </ul>
         */
        @Headers("Content-Type: application/json")
        @POST("auth/signIn")
        Observable<Response<UserSessionInfo>> signIn(@Body SignInBody body);

        @Headers("Content-Type: application/json")
        @POST("subpopulations/" + STUDY_ID + "/consents/signature")
        Observable<Response<BridgeMessageResponse>> consentSignature(@Body ConsentSignatureBody body);

        /**
         * @return Response code <b>200</b> w/ message explaining instructions on how the user should
         * proceed
         */
        @Headers("Content-Type: application/json")
        @POST("auth/requestResetPassword")
        Observable<Response> requestResetPassword(@Body EmailBody body);


        @POST("subpopulations/" + STUDY_ID + "/consents/signature/withdraw")
        Observable<Response<BridgeMessageResponse>> withdrawConsent(@Body WithdrawalBody withdrawal);

        /**
         * @return Response code <b>200</b> w/ message explaining instructions on how the user should
         * proceed
         */
        @Headers("Content-Type: application/json")
        @POST("auth/resendEmailVerification")
        Observable<DataResponse> resendEmailVerification(@Body EmailBody body);

        /**
         * @return Response code 200 w/ message telling user has been signed out
         */
        @POST("auth/signOut")
        Observable<Response> signOut();

        @POST("users/self/dataSharing")
        Observable<Response<BridgeMessageResponse>> dataSharing(@Body SharingOptionBody body);

        @Headers("Content-Type: application/json")
        @POST("surveyresponses")
        Observable<IdentifierHolder> surveyResponses(@Body SurveyResponse body);

        @Headers("Content-Type: application/json")
        @POST("uploads")
        Observable<UploadSession> requestUploadSession(@Body UploadRequest body);

        @POST("uploads/{id}/complete")
        Observable<BridgeMessageResponse> uploadComplete(@Path("id") String id);

        @GET("uploadstatuses/{id}")
        Observable<UploadValidationStatus> uploadStatus(@Path("id") String id);
    }

}
