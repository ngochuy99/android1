package net.cyclestreets.api.client;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

public interface OpenAPI {

  @GET("/v2/directions/cycling-regular")
  Call<String> getJourneyJson(@Query("api_key") String key,
                              @Query("start") String start,
                              @Query("end") String end);
}
