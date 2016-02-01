package co.touchlab.researchstack.sampleapp;

import android.content.Context;
import android.support.annotation.Nullable;

import com.google.gson.Gson;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import co.touchlab.researchstack.core.StorageAccess;
import co.touchlab.researchstack.core.helpers.LogExt;
import co.touchlab.researchstack.core.result.StepResult;
import co.touchlab.researchstack.core.result.TaskResult;
import co.touchlab.researchstack.core.storage.database.AppDatabase;
import co.touchlab.researchstack.core.storage.file.FileAccessException;
import co.touchlab.researchstack.core.utils.FormatHelper;
import co.touchlab.researchstack.core.utils.ObservableUtils;
import co.touchlab.researchstack.glue.DataProvider;
import co.touchlab.researchstack.glue.DataResponse;
import co.touchlab.researchstack.glue.model.SchedulesAndTasksModel;
import co.touchlab.researchstack.glue.model.TaskModel;
import co.touchlab.researchstack.glue.model.User;
import co.touchlab.researchstack.glue.schedule.ScheduleHelper;
import co.touchlab.researchstack.glue.task.SmartSurveyTask;
import co.touchlab.researchstack.glue.ui.scene.SignInStepLayout;
import co.touchlab.researchstack.glue.utils.JsonUtils;
import co.touchlab.researchstack.sampleapp.bridge.BridgeMessageResponse;
import co.touchlab.researchstack.sampleapp.bridge.IdentifierHolder;
import co.touchlab.researchstack.sampleapp.network.UserSessionInfo;
import co.touchlab.researchstack.sampleapp.network.body.ConsentSignatureBody;
import co.touchlab.researchstack.sampleapp.network.body.EmailBody;
import co.touchlab.researchstack.sampleapp.network.body.SignInBody;
import co.touchlab.researchstack.sampleapp.network.body.SignUpBody;
import co.touchlab.researchstack.sampleapp.network.body.SurveyAnswer;
import co.touchlab.researchstack.sampleapp.network.body.SurveyResponse;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.GsonConverterFactory;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.RxJavaCallAdapterFactory;
import retrofit2.http.Body;
import retrofit2.http.POST;
import rx.Observable;

public class SampleDataProvider extends DataProvider
{
    public static final String TEMP_USER_JSON_FILE_NAME    = "/temp_user";
    public static final String TEMP_CONSENT_JSON_FILE_NAME = "/consent_sig";
    public static final String TEMP_USER_EMAIL             = "/user_email";
    public static final String USER_SESSION_PATH           = "/user_session";

    //TODO Add build flavors, add var to BuildConfig for STUDY_ID
    public static final  String STUDY_ID = "ohsu-molemapper";
    private static final String CLIENT   = "android";

    //TODO Add build flavors, add var to BuildConfig for BASE_URL
    String BASE_URL = "https://webservices-staging.sagebridge.org/v3/";

    private BridgeService   service;
    private UserSessionInfo userSessionInfo;
    private Gson    gson     = new Gson();
    private boolean signedIn = false;
    private String userEmail;

    // TODO figure out if there's a better way to do this
    // these are used to get task/step guids without rereading the json files and iterating through
    private Map<String, TaskModel> loadedTasks     = new HashMap<>();
    private Map<String, String>    loadedTaskGuids = new HashMap<>();

    public SampleDataProvider()
    {
        buildRetrofitService(null);
    }

    private void buildRetrofitService(UserSessionInfo userSessionInfo)
    {
        HttpLoggingInterceptor interceptor = new HttpLoggingInterceptor(message -> LogExt.i(
                SignInStepLayout.class,
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
                    .header("Content-Type", "application/json")
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
            userEmail = loadUserEmail(context);

            buildRetrofitService(userSessionInfo);
            subscriber.onNext(new DataResponse(true, null));

            if(userSessionInfo != null && ! userSessionInfo.isConsented())
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
        });
    }

    @Override
    public Observable<DataResponse> signUp(Context context, String email, String username, String password)
    {
        //TODO pass in data groups, remove roles
        SignUpBody body = new SignUpBody(STUDY_ID, email, username, password, null, null);
        saveUserEmail(context, email);
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
                saveUserSession(context, userSessionInfo);
                buildRetrofitService(userSessionInfo);
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
        return userEmail != null;
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

        String jsonString = gson.toJson(signature);

        LogExt.d(getClass(), "Writing user json:\n" + signature);

        writeJsonString(context, jsonString, TEMP_CONSENT_JSON_FILE_NAME);
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
        return userEmail;
    }

    // TODO this is a temporary solution
    private void saveUserEmail(Context context, String email)
    {
        writeJsonString(context, email, TEMP_USER_EMAIL);
    }

    @Nullable
    private String loadUserEmail(Context context)
    {
        String email = null;
        try
        {
            email = loadJsonString(context, TEMP_USER_EMAIL);
        }
        catch(FileAccessException e)
        {
            LogExt.w(getClass(), "TEMP USER EMAIL not readable");
        }
        return email;
    }

    private void saveUserSession(Context context, UserSessionInfo userInfo)
    {
        String userSessionJson = gson.toJson(userInfo);
        writeJsonString(context, userSessionJson, USER_SESSION_PATH);
    }

    private void writeJsonString(Context context, String userSessionJson, String userSessionPath)
    {
        StorageAccess.getFileAccess()
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
        return new String(StorageAccess.getFileAccess().readData(context, path));
    }

    @Override
    public List<SchedulesAndTasksModel.TaskModel> loadTasksAndSchedules(Context context)
    {
        SchedulesAndTasksModel schedulesAndTasksModel = JsonUtils.loadClass(context,
                SchedulesAndTasksModel.class,
                "tasks_and_schedules");

        AppDatabase db = StorageAccess.getAppDatabase();

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
        StorageAccess.getAppDatabase().saveTaskResult(taskResult);

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
        // TODO handle errors, add queue?
        service.surveyResponses(response)
                .compose(ObservableUtils.applyDefault())
                .subscribe(httpResponse -> LogExt.d(getClass(),
                        "Successful upload of survey, identifier: " + httpResponse.identifier),
                        error -> {
                            LogExt.e(getClass(), "Error uploading survey");
                            error.printStackTrace();
                        });
    }

    /**
     * TODO use this for deciding what info to collect during signup, hardcoded in layouts for now
     */
    @Override
    public User.UserInfoType[] getUserInfoTypes()
    {
        return new User.UserInfoType[] {
                User.UserInfoType.Name,
                User.UserInfoType.Email,
                User.UserInfoType.BiologicalSex,
                User.UserInfoType.DateOfBirth,
                User.UserInfoType.Height,
                User.UserInfoType.Weight
        };
    }

    @Deprecated
    public void clearUserData(Context context)
    {
        // TODO make this work again
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
        @POST("auth/signIn")
        Observable<Response<UserSessionInfo>> signIn(@Body SignInBody body);

        @POST("subpopulations/" + STUDY_ID + "/consents/signature")
        Observable<Response<BridgeMessageResponse>> consentSignature(@Body ConsentSignatureBody body);

        /**
         * @return Response code <b>200</b> w/ message explaining instructions on how the user should
         * proceed
         */
        @POST("auth/requestResetPassword")
        Observable<Response> requestResetPassword(@Body EmailBody body);

        /**
         * @return Response code <b>200</b> w/ message explaining instructions on how the user should
         * proceed
         */
        @POST("auth/resendEmailVerification")
        Observable<DataResponse> resendEmailVerification(@Body EmailBody body);

        /**
         * @return Response code 200 w/ message telling user has been signed out
         */
        @POST("auth/signOut")
        Observable<Response> signOut();

        @POST("surveyresponses")
        Observable<IdentifierHolder> surveyResponses(@Body SurveyResponse body);
    }

}