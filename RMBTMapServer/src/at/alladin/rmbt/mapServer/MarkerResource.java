/*******************************************************************************
 * Copyright 2013 alladin-IT OG
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
 ******************************************************************************/
package at.alladin.rmbt.mapServer;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.Format;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.TimeZone;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.resource.Get;
import org.restlet.resource.Post;

import at.alladin.rmbt.mapServer.MapServerOptions.MapFilter;
import at.alladin.rmbt.mapServer.MapServerOptions.MapOption;
import at.alladin.rmbt.mapServer.MapServerOptions.SQLFilter;
import at.alladin.rmbt.shared.Classification;
import at.alladin.rmbt.shared.Helperfunctions;
import at.alladin.rmbt.shared.SignificantFormat;

import com.google.common.base.Strings;

public class MarkerResource extends ServerResource
{
    
    private static int CLICK_RADIUS = 10;
    
    @Post("json")
    public String request(final String entity)
    {
        addAllowOrigin();
        
        JSONObject request = null;
        
        final JSONObject answer = new JSONObject();
        
        if (entity != null && !entity.isEmpty())
            // try parse the string to a JSON object
            try
            {
                request = new JSONObject(entity);
                
                String lang = request.optString("language");
                
                // Load Language Files for Client
                
                final List<String> langs = Arrays.asList(settings.getString("RMBT_SUPPORTED_LANGUAGES").split(",\\s*"));
                
                if (langs.contains(lang))
                    labels = (PropertyResourceBundle) ResourceBundle.getBundle("at.alladin.rmbt.res.SystemMessages",
                            new Locale(lang));
                else
                    lang = settings.getString("RMBT_DEFAULT_LANGUAGE");
                
//                System.out.println(request.toString(4));
                
                final JSONObject coords = request.getJSONObject("coords");
                
                final int zoom;
                double geo_x = 0;
                double geo_y = 0;
                int size = 0;
                
                boolean useXY = false;
                boolean useLatLon = false;
                
                if (coords.has("x") && coords.has("y"))
                    useXY = true;
                else if (coords.has("lat") && coords.has("lon"))
                    useLatLon = true;
                
                if (coords.has("z") && (useXY || useLatLon))
                {
                    zoom = coords.optInt("z");
                    if (useXY)
                    {
                        geo_x = coords.optDouble("x");
                        geo_y = coords.optDouble("y");
                    }
                    else if (useLatLon)
                    {
                        final double tmpLat = coords.optDouble("lat");
                        final double tmpLon = coords.optDouble("lon");
                        geo_x = GeoCalc.lonToMeters(tmpLon);
                        geo_y = GeoCalc.latToMeters(tmpLat);
//                        System.out.println(String.format("using %f/%f", geo_x, geo_y));
                    }
                    
                    if (coords.has("size"))
                        size = coords.getInt("size");
                    
                    if (zoom != 0 && geo_x != 0 && geo_y != 0)
                    {
                        double radius = 0;
                        if (size > 0)
                            radius = size * GeoCalc.getResFromZoom(256, zoom); // TODO use real tile size
                        else
                            radius = CLICK_RADIUS * GeoCalc.getResFromZoom(256, zoom);  // TODO use real tile size
                        final double geo_x_min = geo_x - radius;
                        final double geo_x_max = geo_x + radius;
                        final double geo_y_min = geo_y - radius;
                        final double geo_y_max = geo_y + radius;
                        
                        String hightlightUUIDString = null;
                        UUID highlightUUID = null;
                        
                        final JSONObject mapOptionsObj = request.getJSONObject("options");
                        String optionStr = mapOptionsObj.optString("map_options");
                        if (optionStr == null || optionStr.length() == 0) // set
                                                                          // default
                            optionStr = "mobile/download";
                        
                        final MapOption mo = MapServerOptions.getMapOptionMap().get(optionStr);
                        
                        final List<SQLFilter> filters = new ArrayList<SQLFilter>(MapServerOptions.getDefaultMapFilters());
                        filters.add(MapServerOptions.getAccuracyMapFilter());
                        
                        final JSONObject mapFilterObj = request.getJSONObject("filter");
                        
                        final Iterator<?> keys = mapFilterObj.keys();
                        
                        while (keys.hasNext())
                        {
                            final String key = (String) keys.next();
                            if (mapFilterObj.get(key) instanceof Object)
                                if (key.equals("highlight"))
                                    hightlightUUIDString = mapFilterObj.getString(key);
                                else
                                {
                                    final MapFilter mapFilter = MapServerOptions.getMapFilterMap().get(key);
                                    if (mapFilter != null)
                                        filters.add(mapFilter.getFilter(mapFilterObj.getString(key)));
                                }
                        }
                        
                        if (hightlightUUIDString != null)
                            try
                            {
                                highlightUUID = UUID.fromString(hightlightUUIDString);
                            }
                            catch (final Exception e)
                            {
                                highlightUUID = null;
                            }
                        
                        if (conn != null)
                        {
                            PreparedStatement ps = null;
                            ResultSet rs = null;
                            
                            final StringBuilder whereSQL = new StringBuilder(mo.sqlFilter);
                            for (final SQLFilter sf : filters)
                                whereSQL.append(" AND ").append(sf.where);
                            
                            final String sql = String
                                    .format("SELECT"
                                            + (useLatLon ? " geo_lat lat, geo_long lon"
                                                    : " ST_X(t.location) x, ST_Y(t.location) y")
                                            + ", t.time, t.timezone, t.speed_download, t.speed_upload, t.ping_shortest, t.network_type, t.signal_strength, t.wifi_ssid, t.network_operator_name, t.network_operator, t.network_sim_operator, t.roaming_type, pMob.shortname mobile_provider_name, prov.shortname provider_text"
                                            + (highlightUUID == null ? "" : " , c.uid, c.uuid")
                                            + " FROM test t"
                                            + " LEFT JOIN provider prov"
                                            + " ON t.provider_id=prov.uid"
                                            + " LEFT JOIN provider pMob"
                                            + " ON t.mobile_provider_id=pMob.uid"
                                            + (highlightUUID == null ? ""
                                                    : " LEFT JOIN client c ON (t.client_id=c.uid AND c.uuid=?)")
                                            + " WHERE"
                                            + " %s"
                                            + " AND location && ST_SetSRID(ST_MakeBox2D(ST_Point(?,?), ST_Point(?,?)), 900913)"
                                            + " ORDER BY" + (highlightUUID == null ? "" : " c.uid ASC,")
                                            + " t.uid DESC" + " LIMIT 5", whereSQL);
                            
//                            System.out.println("SQL: " + sql);
                            ps = conn.prepareStatement(sql);
                            
                            int i = 1;
                            
                            if (highlightUUID != null)
                                ps.setObject(i++, highlightUUID);
                            
                            for (final SQLFilter sf : filters)
                                i = sf.fillParams(i, ps);
                            ps.setDouble(i++, geo_x_min);
                            ps.setDouble(i++, geo_y_min);
                            ps.setDouble(i++, geo_x_max);
                            ps.setDouble(i++, geo_y_max);
                            
//                            System.out.println("SQL: " + ps.toString());
                            if (ps.execute())
                            {
                                
                                final Locale locale = new Locale(lang);
                                final Format format = new SignificantFormat(2, locale);
                                
                                final JSONArray resultList = new JSONArray();
                                
                                rs = ps.getResultSet();
                                
                                while (rs.next())
                                {
                                    final JSONObject jsonItem = new JSONObject();
                                    
                                    JSONArray jsonItemList = new JSONArray();
                                    
                                    // RMBTClient Info
                                    if (highlightUUID != null && rs.getString("uuid") != null)
                                        jsonItem.put("highlight", true);
                                    
                                    final double res_x = rs.getDouble(1);
                                    final double res_y = rs.getDouble(2);
                                    
                                    jsonItem.put("lat", res_x);
                                    jsonItem.put("lon", res_y);
                                    // marker.put("uid", uid);
                                    
                                    final Date date = rs.getTimestamp("time");
                                    final String tzString = rs.getString("timezone");
                                    final TimeZone tz = TimeZone.getTimeZone(tzString);
                                    final DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM,
                                            DateFormat.MEDIUM, locale);
                                    dateFormat.setTimeZone(tz);
                                    jsonItem.put("time_string", dateFormat.format(date));
                                    
                                    final int fieldDown = rs.getInt("speed_download");
                                    JSONObject singleItem = new JSONObject();
                                    singleItem.put("title", labels.getString("RESULT_DOWNLOAD"));
                                    final String downloadString = String.format("%s %s",
                                            format.format(fieldDown / 1000d), labels.getString("RESULT_DOWNLOAD_UNIT"));
                                    singleItem.put("value", downloadString);
                                    singleItem.put("classification",
                                            Classification.classify(Classification.THRESHOLD_DOWNLOAD, fieldDown));
                                    // singleItem.put("help", "www.rtr.at");
                                    
                                    jsonItemList.put(singleItem);
                                    
                                    final int fieldUp = rs.getInt("speed_upload");
                                    singleItem = new JSONObject();
                                    singleItem.put("title", labels.getString("RESULT_UPLOAD"));
                                    final String uploadString = String.format("%s %s", format.format(fieldUp / 1000d),
                                            labels.getString("RESULT_UPLOAD_UNIT"));
                                    singleItem.put("value", uploadString);
                                    singleItem.put("classification",
                                            Classification.classify(Classification.THRESHOLD_UPLOAD, fieldUp));
                                    // singleItem.put("help", "www.rtr.at");
                                    
                                    jsonItemList.put(singleItem);
                                    
                                    final long fieldPing = rs.getLong("ping_shortest");
                                    final int pingValue = (int) Math.round(rs.getDouble("ping_shortest") / 1000000d);
                                    singleItem = new JSONObject();
                                    singleItem.put("title", labels.getString("RESULT_PING"));
                                    final String pingString = String.format("%s %s", format.format(pingValue),
                                            labels.getString("RESULT_PING_UNIT"));
                                    singleItem.put("value", pingString);
                                    singleItem.put("classification",
                                            Classification.classify(Classification.THRESHOLD_PING, fieldPing));
                                    // singleItem.put("help", "www.rtr.at");
                                    
                                    jsonItemList.put(singleItem);
                                    
                                    final int networkType = rs.getInt("network_type");
                                    
                                    final String signalField = rs.getString("signal_strength");
                                    if (signalField != null && signalField.length() != 0)
                                    {
                                        final int signalValue = rs.getInt("signal_strength");
                                        final int[] threshold = networkType == 99 || networkType == 0 ? Classification.THRESHOLD_SIGNAL_WIFI
                                                : Classification.THRESHOLD_SIGNAL_MOBILE;
                                        singleItem = new JSONObject();
                                        singleItem.put("title", labels.getString("RESULT_SIGNAL"));
                                        singleItem.put("value",
                                                signalValue + " " + labels.getString("RESULT_SIGNAL_UNIT"));
                                        singleItem.put("classification",
                                                Classification.classify(threshold, signalValue));
                                        jsonItemList.put(singleItem);
                                    }
                                    
                                    jsonItem.put("measurement", jsonItemList);
                                    
                                    jsonItemList = new JSONArray();
                                    
                                    singleItem = new JSONObject();
                                    singleItem.put("title", labels.getString("RESULT_NETWORK_TYPE"));
                                    singleItem.put("value", Helperfunctions.getNetworkTypeName(networkType));
                                    
                                    jsonItemList.put(singleItem);
                                    
                                    
                                    if (networkType == 98 || networkType == 99) // mobile wifi or browser
                                    {
                                        final String providerText = rs.getString("provider_text");
                                        if (! Strings.isNullOrEmpty(providerText))
                                        {
                                            singleItem = new JSONObject();
                                            singleItem.put("title", labels.getString("RESULT_PROVIDER"));
                                            singleItem.put("value", providerText);
                                            jsonItemList.put(singleItem);
                                        }
                                        if (networkType == 99)  // mobile wifi
                                        {
                                            if (highlightUUID != null && rs.getString("uuid") != null) // own test
                                            {
                                                final String ssid = rs.getString("wifi_ssid");
                                                if (ssid != null && ssid.length() != 0)
                                                {
                                                    singleItem = new JSONObject();
                                                    singleItem.put("title", labels.getString("RESULT_WIFI_SSID"));
                                                    singleItem.put("value", ssid.toString());
                                                    jsonItemList.put(singleItem);
                                                }
                                            }
                                        }
                                    }
                                    else // mobile
                                    {
                                        String networkOperator = rs.getString("network_operator");
                                        String mobileProviderName = rs.getString("mobile_provider_name");
                                        if (! Strings.isNullOrEmpty(networkOperator))
                                        {
                                            final String mobileNetworkString;
                                            if (Strings.isNullOrEmpty(mobileProviderName))
                                                mobileNetworkString = networkOperator;
                                            else
                                                mobileNetworkString = String.format("%s (%s)", mobileProviderName, networkOperator);
                                            
                                            singleItem = new JSONObject();
                                            singleItem.put("title", labels.getString("RESULT_OPERATOR_NAME"));
                                            singleItem.put("value", mobileNetworkString);
                                            jsonItemList.put(singleItem);
                                        }
                                        
                                        final int roamingType = rs.getInt("roaming_type");
                                        if (roamingType > 0)
                                        {
                                            singleItem = new JSONObject();
                                            singleItem.put("title", labels.getString("RESULT_ROAMING"));
                                            singleItem.put("value", Helperfunctions.getRoamingType(labels, roamingType));
                                            jsonItemList.put(singleItem);
                                        }
                                    }
                                    
                                    jsonItem.put("net", jsonItemList);
                                    
                                    resultList.put(jsonItem);
                                    
                                    if (resultList.length() == 0)
                                        System.out.println("Error getting Results.");
                                    // errorList.addError(MessageFormat.format(labels.getString("ERROR_DB_GET_CLIENT"),
                                    // new Object[] {uuid}));
                                    
                                }
                                
                                answer.put("measurements", resultList);
                            }
                            else
                                System.out.println("Error executing SQL.");
                        }
                        else
                            System.out.println("No Database Connection.");
                    }
                }
                else
                    System.out.println("Expected request is missing.");
                
            }
            catch (final JSONException e)
            {
                System.out.println("Error parsing JSDON Data " + e.toString());
            }
            catch (final SQLException e)
            {
                e.printStackTrace();
            }
        else
            System.out.println("No Request.");
        
        return answer.toString();
        
    }
    
    @Get("json")
    public String retrieve(final String entity)
    {
        return request(entity);
    }
    
}