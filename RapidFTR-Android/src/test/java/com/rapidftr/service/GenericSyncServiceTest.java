package com.rapidftr.service;

import android.graphics.BitmapFactory;
import com.google.common.io.CharStreams;
import com.rapidftr.CustomTestRunner;
import com.rapidftr.RapidFtrApplication;
import com.rapidftr.database.Database;
import com.rapidftr.model.Child;
import com.rapidftr.model.History;
import com.rapidftr.model.User;
import com.rapidftr.repository.ChildRepository;
import com.rapidftr.utils.AudioCaptureHelper;
import com.rapidftr.utils.PhotoCaptureHelper;
import com.rapidftr.utils.http.FluentRequest;
import org.apache.http.HttpException;
import org.json.JSONArray;
import org.json.JSONException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.robolectric.Robolectric;
import org.robolectric.tester.org.apache.http.FakeHttpLayer;
import org.robolectric.tester.org.apache.http.TestHttpResponse;

import javax.xml.ws.http.HTTPException;
import java.io.*;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.rapidftr.RapidFtrApplication.SERVER_URL_PREF;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.robolectric.Robolectric.getFakeHttpLayer;

@RunWith(CustomTestRunner.class)
public class GenericSyncServiceTest {
    @Mock
    private ChildRepository repository;
    @Mock
    private User currentUser;

    private RapidFtrApplication application;

    FluentRequest fluentRequest;
    public static final String RESPONSE = "{\"unique_identifier\":\"adf7c0c9-0137-4cae-beea-b7d282344829\",\"created_at\":\"2013-02-08 12:18:37\",\"created_by_full_name\":\"RapidFTR\",\"couchrest-type\":\"Child\",\"short_id\":\"2344829\",\"_id\":\"b7f89b978870da823e0af6491c3e295b\",\"_rev\":\"2-bc72af384e177fcaa8e9e8d181bfe05b\",\"name\":\"\",\"last_updated_at\":\"2013-02-08 11:37:33\",\"current_photo_key\":\"photo--1475374810-2013-02-08T175138\",\"created_by\":\"rapidftr\",\"photo_keys\":[\"photo--1475374810-2013-02-08T175138\"],\"created_organisation\":\"N/A\",\"posted_at\":\"2013-02-08 12:16:55UTC\",\"last_updated_by_full_name\":\"RapidFTR\"}";
    EntityHttpDao<Child> childHttpDao;
    MediaSyncHelper mediaSyncHelper;
    GenericSyncService<Child> genericSyncService;

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        application = (RapidFtrApplication) Robolectric.getShadowApplication().getApplicationContext();
        User user = new User("userName", "password", true, "http://1.2.3.4");
        application.setCurrentUser(user);

        childHttpDao = EntityHttpDaoFactory.createChildHttpDao(application,
                "http://whatever",
                ChildSyncService.CHILDREN_API_PATH,
                ChildSyncService.CHILDREN_API_PARAMETER);
        given(currentUser.isVerified()).willReturn(true);
        fluentRequest = new FluentRequest();
        mediaSyncHelper = new MediaSyncHelper(childHttpDao, mockContext());
        genericSyncService = new GenericSyncService<Child>(mediaSyncHelper, childHttpDao, repository);
    }

    @Test
    public void shouldMarkChildAsSyncedWhenSyncing() throws IOException, JSONException, GeneralSecurityException {
        getFakeHttpLayer().setDefaultHttpResponse(201, "{}");
        Child child = genericSyncService.sync(new Child(), "");
        assertThat(child.isSynced(), is(true));
    }

    @Test
    public void shouldCreateOrUpdateChildWithoutHistoryWhenSyncing() throws IOException, JSONException, GeneralSecurityException {
        getFakeHttpLayer().setDefaultHttpResponse(201, "{}");
        Child child = genericSyncService.sync(new Child(), "");
        verify(repository).createOrUpdateWithoutHistory(child);
    }

    @Test(expected = SyncFailedException.class)
    public void shouldThrowSyncFailedExceptionWhenSyncFailure() throws Exception {
        Child child = new Child();
        getFakeHttpLayer().setDefaultHttpResponse(503, "error");
        genericSyncService.sync(child, "");
    }

    @Test
    public void shouldSyncUsingTheProvidedPath() throws Exception {
        Child child = new Child("id1", "user1", "{ 'test1' : 'value1' }");
        child.put(Database.ChildTableColumn.internal_id.getColumnName(), "xyz");

        getFakeHttpLayer().addHttpResponseRule("http://whatever/api/children/xyz", "{}");
        genericSyncService.sync(child, "/api/children/xyz");
    }

    @Test
    public void shouldCreateNewChildIfThereIsNoID() throws Exception {
        Child child = new Child("id1", "user1", "{ 'test1' : 'value1' }");
        getFakeHttpLayer().addHttpResponseRule("http://whatever/api/children", "{ 'test1' : 'value2', '_id' : 'abcd1234'}");
        child = genericSyncService.sync(child, "/api/children");
        verify(repository).createOrUpdateWithoutHistory(child);
    }

    @Test
    public void shouldAddPhotoKeysToParam() throws JSONException, IOException, GeneralSecurityException, HttpException {
        getFakeHttpLayer().setDefaultHttpResponse(201, RESPONSE);
        List<String> photoKeysList = Arrays.asList("photo-998877", "photo-998547", "abcd123", "1234ABC");
        Child child = new Child("id1", "user1", String.format("{ '_id' : 'abcdef', 'name' : 'child1', 'test2' : 0, 'current_photo_key' : '1234ABC', 'photo_keys' : %s}", new JSONArray(photoKeysList).toString()));
        EntityHttpDao<Child> httpDao = new EntityHttpDao<Child>(application, "", "", "child");
        EntityHttpDao<Child> spyHttpDao = spy(httpDao);
        doReturn(child).when(spyHttpDao).update(any(Child.class), any(String.class), any(Map.class));

        new GenericSyncService<Child>(mediaSyncHelper, spyHttpDao, repository).sync(child, "");

        verify(spyHttpDao).update(any(Child.class), any(String.class), argThat(new ContainsPhotoKeysParams("abcd123", "1234ABC")));
    }

    @Test
    public void shouldNotAddPhotoParamIfThePhotoNameIsPresentInPhotoKeys() throws JSONException, IOException, GeneralSecurityException {
        RapidFtrApplication context = mockContext();

        String photoKeys = new JSONArray(Arrays.asList("photo-998877", "photo-998547", "1234ABC")).toString();
        String childDetails = String.format("{ 'name' : 'child1', 'test2' : 0, 'current_photo_key' : '1234ABC', 'photo_keys' : %s}", photoKeys);
        Child child = new Child("id1", "user1", childDetails);

        AudioCaptureHelper audioCaptureHelper = new AudioCaptureHelper(context);
        audioCaptureHelper.saveAudio(child, new ByteArrayInputStream("OK".getBytes()));

        byte[] content = "Testing".getBytes();

        PhotoCaptureHelper photoCaptureHelper = new PhotoCaptureHelper(context);
        photoCaptureHelper.savePhoto(BitmapFactory.decodeByteArray(content, 0, content.length), 10, "photo-998877");
        photoCaptureHelper.savePhoto(BitmapFactory.decodeByteArray(content, 0, content.length), 10, "photo-998547");
        photoCaptureHelper.savePhoto(BitmapFactory.decodeByteArray(content, 0, content.length), 10, "1234ABC");

        getFakeHttpLayer().addHttpResponseRule("http://whatever/api/children", "{}");
        getFakeHttpLayer().hasRequestMatchingRule(new FakeHttpLayer.RequestMatcherBuilder().param("current_photo_key", "1234ABC"));

        genericSyncService.sync(child, "api/children");
    }

    @Test
    public void shouldAddAudioRecordedIfCreatedOnTheMobile() throws JSONException, IOException {
        RapidFtrApplication context = mockContext();

        String response = "{'_id': 'testing', 'name' : 'child1', 'recorded_audio' : '123456'}";
        Child child = new Child("id", "user", "{'name' : 'child1', 'recorded_audio' : '123455'}");

        getFakeHttpLayer().addHttpResponseRule("http://whatever/api/children", response);
        getFakeHttpLayer().addHttpResponseRule("http://whatever/child/testing/audio", "RECEIVED");

        AudioCaptureHelper audioCaptureHelper = new AudioCaptureHelper(context);
        audioCaptureHelper.saveAudio(child, new ByteArrayInputStream("OK".getBytes()));

        genericSyncService.sync(child, "api/children");

        File file = audioCaptureHelper.getFile("123456", "");
        String fileContent = CharStreams.toString(new InputStreamReader(new FileInputStream(file)));
        assertEquals("RECEIVED", fileContent);
    }

    @Test
    public void shouldNotAddAudioRecordedToTheRequestIfItsAlreadyPresentInServer() throws JSONException, IOException, HttpException {
        getFakeHttpLayer().setDefaultHttpResponse(201, RESPONSE);
        Child child = new Child("id", "user", "{'_id' : 'abcd123488', 'name' : 'child1', 'recorded_audio' : '123455', 'audio_attachments' : {'original' : '123455', 'amr':'123455'}}");
        EntityHttpDao<Child> httpDao = new EntityHttpDao<Child>(application, "", "", "child");
        EntityHttpDao<Child> spyHttpDao = spy(httpDao);
        doReturn(child).when(spyHttpDao).update(any(Child.class), any(String.class), any(Map.class));

        new GenericSyncService<Child>(mediaSyncHelper, spyHttpDao, repository).sync(child, "api/children");

        verify(spyHttpDao).update(any(Child.class), any(String.class), argThat(new DoesntContainAudioKeyParams()));
    }

    @Test
    public void shouldRemoveUnusedParametersBeforeSync() throws JSONException, IOException, GeneralSecurityException {
        RapidFtrApplication context = mockContext();
        String photoKeys = new JSONArray(Arrays.asList("photo-998877", "photo-998547", "1234ABC")).toString();
        String childDetails = String.format("{ 'name' : 'child1', 'test2' : 0, 'current_photo_key' : '1234ABC', 'recorded_audio' : '123455', 'audio_attachments' : {'original' : '123455', 'amr':'123455'}, 'photo_keys' : %s}", photoKeys);
        Child child = new Child("id", "user", childDetails);

        AudioCaptureHelper audioCaptureHelper = new AudioCaptureHelper(context);
        audioCaptureHelper.saveAudio(child, new ByteArrayInputStream("OK".getBytes()));

        byte[] content = "Testing".getBytes();

        PhotoCaptureHelper photoCaptureHelper = new PhotoCaptureHelper(context);
        photoCaptureHelper.savePhoto(BitmapFactory.decodeByteArray(content, 0, content.length), 10, "photo-998877");
        photoCaptureHelper.savePhoto(BitmapFactory.decodeByteArray(content, 0, content.length), 10, "photo-998547");
        photoCaptureHelper.savePhoto(BitmapFactory.decodeByteArray(content, 0, content.length), 10, "1234ABC");

        getFakeHttpLayer().addHttpResponseRule("http://whatever/api/children", "{}");

        genericSyncService.sync(child, "api/children");
        assertThat(child.optString("photo_keys"), is(""));
        assertThat(child.optString("audio_attachments"), is(""));
        assertThat(child.optString("synced"), is(""));
    }

    @Test
    public void shouldUpdateChildAttributesAfterSync() throws IOException, JSONException {
        RapidFtrApplication context = mockContext();

        String response = "{\"separation_place\":\"\",\"wishes_address_3\":\"\",\"care_arrangments_name\":\"\",\"other_family\":\"\",\"care_arrangements_knowsfamily\":\"\",\"created_at\":\"2012-12-14 10:57:39UTC\",\"wishes_contacted_details\":\"\",\"posted_from\":\"Browser\",\"care_arrangements_relationship\":\"\",\"interviewer\":\"\",\"birthplace\":\"\",\"father_death_details\":\"\",\"mothers_name\":\"\",\"name\":\"kavitha working\",\"other_child_2_relationship\":\"\",\"other_child_1\":\"\",\"other_child_1_dob\":\"\",\"other_child_2_telephone\":\"\",\"caregivers_name\":\"\",\"other_child_3_dob\":\"\",\"concerns_medical_case\":\"\",\"names_origin\":\"\",\"gender\":\"\",\"unique_identifier\":\"8a126c33-d2e3-4802-8698-19c06f52d5d1\",\"is_caregiver_alive\":\"\",\"wishes_contacted\":\"\",\"other_child_3_address\":\"\",\"evacuation_from\":\"\",\"photo_keys\":[],\"address\":\"\",\"disclosure_other_orgs\":\"\",\"concerns_other\":\"\",\"histories\":[],\"wishes_wants_contact\":\"\",\"wishes_telephone_1\":\"\",\"posted_at\":\"2012-12-14 10:57:39UTC\",\"other_child_1_address\":\"\",\"other_child_3_birthplace\":\"\",\"other_child_3_relationship\":\"\",\"languages\":\"\",\"concerns_followup_details\":\"\",\"other_org_interview_status\":\"\",\"concerns_further_info\":\"\",\"concerns_needs_followup\":\"\",\"disclosure_public_photo\":\"\",\"wishes_name_1\":\"\",\"created_by\":\"rapidftr\",\"other_org_date\":\"\",\"wishes_address_1\":\"\",\"is_mother_alive\":\"\",\"other_child_1_relationship\":\"\",\"other_child_1_telephone\":\"\",\"interview_place\":\"\",\"evacuation_date\":\"\",\"evacuation_status\":\"\",\"other_child_2\":\"\",\"c206ec4e\":\"\",\"other_child_2_dob\":\"\",\"interviewers_org\":\"\",\"dob_or_age\":\"\",\"id_document\":\"\",\"care_arrangements_arrival_date\":\"\",\"rc_id_no\":\"\",\"care_arrangements_came_from\":\"\",\"protection_status\":\"\",\"other_org_place\":\"\",\"separation_date\":\"\",\"created_organisation\":\"N/A\",\"mother_death_details\":\"\",\"concerns_girl_mother\":\"\",\"e96c289e\":\"\",\"orther_org_reference_no\":\"\",\"_rev\":\"1-ec347c93b262e7db0e306b77f22c2e19\",\"evacuation_to\":\"\",\"disclosure_authorities\":\"\",\"c9fc0344\":\"\",\"wishes_telephone_2\":\"\",\"interview_date\":\"\",\"telephone\":\"\",\"evacuation_agent\":\"\",\"additional_tracing_info\":\"\",\"couchrest-type\":\"Child\",\"care_arrangements\":\"\",\"other_child_2_birthplace\":\"\",\"disclosure_public_relatives\":\"\",\"other_child_2_address\":\"\",\"wishes_name_2\":\"\",\"current_photo_key\":\"\",\"disclosure_public_name\":\"\",\"separation_details\":\"\",\"interview_subject_details\":\"\",\"wishes_address_2\":\"\",\"concerns_abuse_situation\":\"\",\"063c3784\":\"\",\"concerns_street_child\":\"\",\"other_child_3\":\"\",\"interview_subject\":\"\",\"care_arrangements_address\":\"\",\"documents\":\"\",\"other_child_1_birthplace\":\"\",\"fef83a5e\":\"\",\"is_father_alive\":\"\",\"created_by_full_name\":\"RapidFTR\",\"characteristics\":\"\",\"care_arrangements_familyinfo\":\"\",\"disclosure_deny_details\":\"\",\"other_org_name\":\"\",\"nationality\":\"\",\"short_id\":\"f52d5d1\",\"concerns_chh\":\"\",\"concerns_vulnerable_person\":\"\",\"wishes_telephone_3\":\"\",\"concerns_disabled\":\"\",\"fathers_name\":\"\",\"_id\":\"0369c92c8e2245e680dc9a580202e285\",\"other_org_country\":\"\",\"ethnicity_or_tribe\":\"\",\"care_arrangements_other\":\"\",\"wishes_name_3\":\"\"}";
        getFakeHttpLayer().setDefaultHttpResponse(200, response);

        Child child = new Child("id", "user", "{ 'name' : 'child1'}");
        Child syncedChild = genericSyncService.sync(child, "");
        assertThat(syncedChild.isSynced(), is(true));
        assertThat(syncedChild.getString("last_synced_at"), not(is(nullValue())));
        assertThat(syncedChild.getString("_attachments"), is(nullValue()));
    }

    @Test
    public void shouldSetMediaIfNotAlreadyExistingOnTheMobile() throws JSONException, IOException {
        RapidFtrApplication context = mockContext();
        String response = "{\"recorded_audio\":\"audio-12321\",\"photo_keys\": [\"photo-998\",\"photo-888\", \"photo-777\"],\"_id\":\"abcd\",\"current_photo_key\": \"photo-888\",\"separation_place\":\"\",\"wishes_address_3\":\"\",\"care_arrangments_name\":\"\",\"other_family\":\"\",\"care_arrangements_knowsfamily\":\"\",\"created_at\":\"2012-12-14 10:57:39UTC\",\"wishes_contacted_details\":\"\",\"posted_from\":\"Browser\"}";
        MediaSyncHelper spyMediaHelper = spy(new MediaSyncHelper(childHttpDao, context));
        GenericSyncService syncService = new GenericSyncService<Child>(spyMediaHelper, childHttpDao, repository);
        getFakeHttpLayer().setDefaultHttpResponse(200, response);
        Child child = new Child("id", "user", "{ 'name' : 'child1'}");

        doNothing().when(spyMediaHelper).getPhotoFromServer(Matchers.any(Child.class), Matchers.any(PhotoCaptureHelper.class), eq("photo-998"));
        doNothing().when(spyMediaHelper).getPhotoFromServer(Matchers.any(Child.class), Matchers.any(PhotoCaptureHelper.class), eq("photo-888"));
        doNothing().when(spyMediaHelper).getPhotoFromServer(Matchers.any(Child.class), Matchers.any(PhotoCaptureHelper.class), eq("photo-777"));

        syncService.sync(child, "");

        verify(spyMediaHelper).getPhotoFromServer(Matchers.any(Child.class), Matchers.any(PhotoCaptureHelper.class), eq("photo-888"));
        verify(spyMediaHelper).getPhotoFromServer(Matchers.any(Child.class), Matchers.any(PhotoCaptureHelper.class), eq("photo-998"));
        verify(spyMediaHelper).getPhotoFromServer(Matchers.any(Child.class), Matchers.any(PhotoCaptureHelper.class), eq("photo-777"));
    }

    @Test
    public void shouldMarkUnverifiedChildAsSyncedOnceSuccessfullySynced() throws Exception {
        getFakeHttpLayer().addHttpResponseRule("http://whatever/api/children/unverified", new TestHttpResponse(200, "{}"));
        Child child = new Child();
        given(currentUser.isVerified()).willReturn(false);

        child = genericSyncService.sync(child, "api/children/unverified");

        assertThat(child.isSynced(), is(true));
    }

    @Test
    public void shouldRemoveHistoriesAfterSuccessfulSync() throws Exception {
        Child childSpy = spy(new Child("{\"_id\" : \"couch_id\", \"child_name\":\"subhas\",\"unique_identifier\":\"78223s4h1e468f5200edc\"}"));
        doReturn(false).when(childSpy).isNew();

        childHttpDao = mock(EntityHttpDao.class);
        doReturn(childSpy).when(childHttpDao).update(eq(childSpy), anyString(), any(Map.class));
        GenericSyncService<Child> syncService = new GenericSyncService<Child>(mediaSyncHelper, childHttpDao, repository);
        syncService.sync(childSpy, "");
        verify(childSpy).remove(History.HISTORIES);
    }

    @Test(expected = SyncFailedException.class)
    public void shouldNotRemoveHistoriesAfterFailedSync() throws Exception {
        Child childSpy = spy(new Child("{\"_id\" : \"couch_id\", \"child_name\":\"subhas\",\"unique_identifier\":\"78223s4h1e468f5200edc\"}"));
        doReturn(false).when(childSpy).isNew();

        childHttpDao = mock(EntityHttpDao.class);
        doThrow(new HTTPException(404)).when(childHttpDao).update(eq(childSpy), anyString(), any(Map.class));
        new GenericSyncService<Child>(mediaSyncHelper, childHttpDao, repository).sync(childSpy, "");
        verify(childSpy, never()).remove(History.HISTORIES);
    }

    private RapidFtrApplication mockContext() {
        RapidFtrApplication context = RapidFtrApplication.getApplicationInstance();
        context.getSharedPreferences().edit().putString(SERVER_URL_PREF, "whatever").commit();
        context.getCurrentUser().setDbKey("tu2347893023u2");
        return context;
    }

    class ContainsPhotoKeysParams extends ArgumentMatcher<Map> {

        private String[] photoKeys;

        public ContainsPhotoKeysParams(String... photoKeys) {
            this.photoKeys = photoKeys;
        }

        public boolean matches(Object params) {
            String actualPhotoKeys = ((Map<String, String>) params).get("photo_keys");
            for (int i = 0; i < photoKeys.length; i++) {
                if (!actualPhotoKeys.matches(".*" + photoKeys[i] + ".*"))
                    return false;
            }
            return true;
        }
    }

    class DoesntContainAudioKeyParams extends ArgumentMatcher<Map> {
        public boolean matches(Object params) {
            return !((Map<String, String>) params).containsKey("recorded_audio");
        }
    }
}
