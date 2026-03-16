package com.stablespringbootproject.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import com.stablespringbootproject.Dto.Stablerequest;
import com.stablespringbootproject.Dto.Stableresponse;
import com.stablespringbootproject.Dto.VendorRegisterRequest;
import com.stablespringbootproject.Entity.Countryentity;
import com.stablespringbootproject.Entity.Countryserviceentity;
import com.stablespringbootproject.Entity.Requestlocation;
import com.stablespringbootproject.Entity.Vehicleresponcemapping;
import com.stablespringbootproject.Entity.Vendorapis;
import com.stablespringbootproject.Entity.Vendorentity;
import com.stablespringbootproject.Entity.vehiclerequestmapping;
import com.stablespringbootproject.repository.Countryrepo;
import com.stablespringbootproject.repository.Countryservicerepo;
import com.stablespringbootproject.repository.Vehiclerequestmappingrepo;
import com.stablespringbootproject.repository.VendorJsonMappingrepo;
import com.stablespringbootproject.repository.Vendorapirepo;
import com.stablespringbootproject.repository.Vendorrepo;

@Service
public class Stableservice {

    private final RestTemplate restTemplate;
    private final Countryrepo countryRepo;
    private final Countryservicerepo stableRepo;
    private final Vendorrepo vendorRepo;
    private final Vendorapirepo vendorApiRepository;
    private final VendorJsonMappingrepo jsonMappingRepo;
    private final Vehiclerequestmappingrepo vehicleRequestMappingRepo;

    public Stableservice(
            RestTemplate restTemplate,
            Countryrepo countryRepo,
            Countryservicerepo stableRepo,
            Vendorrepo vendorRepo,
            Vendorapirepo vendorApiRepository,
            VendorJsonMappingrepo jsonMappingRepo,
            Vehiclerequestmappingrepo vehicleRequestMappingRepo
    ) {
        this.restTemplate = restTemplate;
        this.countryRepo = countryRepo;
        this.stableRepo = stableRepo;
        this.vendorRepo = vendorRepo;
        this.vendorApiRepository = vendorApiRepository;
        this.jsonMappingRepo = jsonMappingRepo;
        this.vehicleRequestMappingRepo = vehicleRequestMappingRepo;
    }

    public Stableresponse fetchVehicle(Stablerequest stablerequest) {

        Countryentity country = countryRepo.findByCountryCode(stablerequest.getCountry())
                .orElseThrow(() -> new RuntimeException("Country Not Found"));

        List<Countryserviceentity> services =
                getActiveServices(country.getCountryCode());

        for (Countryserviceentity service : services) {
            try {
                Vendorentity vendor = findVendorOrThrow(stablerequest);

                List<Vendorapis> vendorApis = vendorApiRepository.findByVendorIdAndApiType(
                        vendor.getId(),
                        stablerequest.getApi_usage_type()
                );

                if (vendorApis == null || vendorApis.isEmpty()) {
                    System.out.println("No Vendor API rows found for vendorId=" + vendor.getId()
                            + " apiType=" + stablerequest.getApi_usage_type());
                } else {
                    for (Vendorapis va : vendorApis) {
                        System.out.println("Vendor API mapped: apiId=" + va.getApiId()
                                + " apiUrl=" + va.getApiUrl()
                                + " httpMethod=" + va.getHttpMethod()
                                + " contentType=" + va.getContentType());
                    }
                }
                Stableresponse response = getVehicleDetailsByVendorRegisteredVehicleAPI(
                        service, stablerequest, vendorApis
                );

                if (response != null) {
                    return response;
                }

            } catch (Exception e) {
                System.out.println("Service failed for country : " + service.getCountryCode());
                throw e;
            }
        }

        throw new RuntimeException("vehicle not found");
    }

    private List<Countryserviceentity> getActiveServices(String countryCode) {

        return stableRepo.findByCountryCodeAndActiveTrue(countryCode);
    }

    private Vendorentity findVendorOrThrow(Stablerequest request) {

        Vendorentity vendor = vendorRepo.findByVendorNameIgnoreCaseAndPhoneNumber(
                request.getVendorname(),
                request.getPhone_number()
        );

        if (vendor == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No Vendor Found for given name and phone number"
            );
        }

        return vendor;
    }

    private Stableresponse getVehicleDetailsByVendorRegisteredVehicleAPI(
            Countryserviceentity service,
            Stablerequest request,
            List<Vendorapis> vendorApis
    ) {

        for (Vendorapis vendorApi : vendorApis) {

            List<vehiclerequestmapping> requestMappings =
                    vehicleRequestMappingRepo.findByVendorIdAndApiId(
                            vendorApi.getVendorId(),
                            vendorApi.getApiId()
                    );

            Map<String, Object> vendorResponse =
                    callVendor(service, vendorApi, requestMappings, request);

            
            
            System.out.println("The response is " + vendorResponse);

            if (vendorResponse != null) {

                List<Vehicleresponcemapping> mappings =
                        jsonMappingRepo.findByApiId(vendorApi.getApiId());

                Stableresponse response = mapVendorResponse(vendorResponse, mappings);

                response.setCountry(service.getCountryCode());

                return response;
            }
        }

        return null;
    }

    private Map<String, Object> callVendor(
            Countryserviceentity service,
            Vendorapis vendorApi,
            List<vehiclerequestmapping> mappings,
            Stablerequest request
    ) {

        // Vendorapis currently stores the relative/full URL in apiUrl
        String url = service.getBaseUrl() + vendorApi.getApiUrl();

        Map<String, String> pathVars = new HashMap<>();
        Map<String, String> queryParams = new HashMap<>();
        Map<String, String> headersMap = new HashMap<>();
        Map<String, Object> bodyJson = new HashMap<>();

        for (vehiclerequestmapping m : mappings) {

            String value = getValueFromStableRequest(
                    m.getStableField(),
                    request,
                    m.getConstantValue()
            );

            if (value == null) continue;

            Requestlocation loc = m.getLocation();
            if (loc == null) continue;

            switch (loc) {
                case PATH:
                    pathVars.put(m.getExternalName(), value);
                    if (m.getExternalName() != null
                            && m.getExternalName().equalsIgnoreCase("vendorname")) {
                        System.out.println("PATH var mapped: vendorname=" + value);
                    }
                    break;

                case QUERY:
                    queryParams.put(m.getExternalName(), value);
                    break;

                case HEADER:
                    headersMap.put(m.getExternalName(), value);
                    break;

                case BODY_JSON:
                    bodyJson.put(m.getExternalName(), value);
                    break;
            }
        }

        // Resolve {pathVars} in the URL. If DB mapping is missing, fall back to Stablerequest
        // for common placeholders (and print when fallback was used).
        Pattern templateVarPattern = Pattern.compile("\\{([^}]+)\\}");
        Matcher matcher = templateVarPattern.matcher(url);
        StringBuffer resolvedUrl = new StringBuffer();
        while (matcher.find()) {
            String placeholder = matcher.group(1);

            System.out.println("Resolving PATH placeholder: '" + placeholder + "'");

            String value = getCaseInsensitive(pathVars, placeholder);
            boolean resolvedFromMapping = false;
            boolean resolvedFromFallback = false;

            if (value != null) {
                resolvedFromMapping = true;
                System.out.println("Resolved from vehiclerequestmapping (PATH) for '" + placeholder + "' = " + value);
            }

            if (value == null) {
                String p = placeholder.trim().toLowerCase();
                if (p.equals("vendorname")) {
                    value = request.getVendorname();
                    if (value != null) {
                        resolvedFromFallback = true;
                        System.out.println("PATH var fallback (not in DB mapping): vendorname=" + value);
                    }
                } else if (p.equals("vehiclenumber") || p.equals("vehicleno") || p.equals("vehicle_number")) {
                    value = request.getVehicleno();
                    if (value != null) {
                        resolvedFromFallback = true;
                        System.out.println("PATH var fallback (not in DB mapping): " + placeholder + "=" + value);
                    }
                } else if (p.equals("country") || p.equals("countrycode")) {
                    value = request.getCountry();
                    if (value != null) {
                        resolvedFromFallback = true;
                        System.out.println("PATH var fallback (not in DB mapping): " + placeholder + "=" + value);
                    }
                }
            }

            if (!resolvedFromMapping && !resolvedFromFallback) {
                System.out.println("No value resolved for placeholder '" + placeholder + "'.");
            }

            if (value == null) {
                System.out.println("Unresolved URL template(s) in: " + url);
                System.out.println("Available PATH vars from vehiclerequestmapping: " + pathVars);
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Missing path variable mapping for '" + placeholder + "'. Provide it in vehiclerequestmapping (PATH) or in request payload."
                );
            }

            String encoded = urlEncode(value);
            matcher.appendReplacement(resolvedUrl, Matcher.quoteReplacement(encoded));
        }
        matcher.appendTail(resolvedUrl);
        url = resolvedUrl.toString();

        // At this point, url should have no remaining {templates}

        if (!queryParams.isEmpty()) {

            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url);

            queryParams.forEach(builder::queryParam);

            url = builder.toUriString();
        }

        HttpHeaders headers = new HttpHeaders();

        headersMap.forEach(headers::add);

        if (vendorApi.getContentType() != null && !vendorApi.getContentType().isEmpty()) {
            try {
                headers.setContentType(
                        MediaType.parseMediaType(vendorApi.getContentType())
                );
            } catch (org.springframework.http.InvalidMediaTypeException
                     | org.springframework.util.InvalidMimeTypeException ex) {
                System.out.println("Invalid contentType in vendor_apis for apiId="
                        + vendorApi.getApiId() + " value='" + vendorApi.getContentType()
                        + "'. Skipping Content-Type header.");
            }
        }

        // FIXED HTTP METHOD HANDLING

        String httpMethodValue = vendorApi.getHttpMethod();

        HttpMethod method;

        if (httpMethodValue == null || httpMethodValue.trim().isEmpty()) {
            method = HttpMethod.GET;
        } else {
            try {
                method = HttpMethod.valueOf(httpMethodValue.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                method = HttpMethod.GET;
            }
        }

        HttpEntity<?> entity;

        if (method == HttpMethod.GET) {
            entity = new HttpEntity<>(headers);
        } else {
            entity = new HttpEntity<>(bodyJson, headers);
        }

        System.out.println("Calling URL : " + url + " with method " + method);

        ResponseEntity<Map> responseEntity =
                restTemplate.exchange(java.net.URI.create(url), method, entity, Map.class);

        return responseEntity.getBody();
    }

    private String getCaseInsensitive(Map<String, String> map, String key) {
        if (map == null || key == null) return null;
        if (map.containsKey(key)) return map.get(key);
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    private String urlEncode(String value) {

        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
        } catch (Exception ex) {
            return value;
        }
    }

    private String getValueFromStableRequest(String stableField, Stablerequest req, String constantValue) {

        if (constantValue != null && !constantValue.isEmpty()) {
            return constantValue;
        }

        if (stableField == null) {
            return null;
        }

        switch (stableField.toUpperCase()) {

            case "VEHICLENO":
                return req.getVehicleno();

            case "COUNTRY":
                return req.getCountry();

            case "VENDORNAME":
                return req.getVendorname();

            case "PHONE_NUMBER":
                return req.getPhone_number();

            case "API_USAGE_TYPE":
                return req.getApi_usage_type();

            default:
                return null;
        }
    }

    private Stableresponse mapVendorResponse(
            Map<String, Object> vendorResponse,
            List<Vehicleresponcemapping> mappings
    ) {

        Stableresponse response = new Stableresponse();

        for (Vehicleresponcemapping mapping : mappings) {

            setIfPresent(
                    vendorResponse.get(mapping.getVehiclenumber()),
                    response::setVehiclenumber
            );

            setIfPresent(
                    vendorResponse.get(mapping.getMake()),
                    response::setMake
            );

            setIfPresent(
                    vendorResponse.get(mapping.getModel()),
                    response::setModel
            );

            setIfPresent(
                    vendorResponse.get(mapping.getYear()),
                    response::setYear
            );
        }

        return response;
    }

    private void setIfPresent(Object value, java.util.function.Consumer<String> setter) {

        if (value != null) {
            setter.accept(value.toString());
        }
    }

    public String registerVendor(VendorRegisterRequest request) {

        Countryentity country = countryRepo.findByCountryCode(request.getCountryCode())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Country Not Found for code: " + request.getCountryCode()
                ));

        Vendorentity existing =
                vendorRepo.findByVendorNameIgnoreCaseAndPhoneNumber(
                        request.getVendorName(),
                        request.getVendorPhone()
                );

        Vendorentity vendorToSave = (existing != null) ? existing : new Vendorentity();

        vendorToSave.setVendorName(request.getVendorName());
        vendorToSave.setPhoneNumber(request.getVendorPhone());

        if (vendorToSave.getActive() == null) {
            vendorToSave.setActive(true);
        }

        Vendorentity savedVendor = vendorRepo.save(vendorToSave);

        Vendorapis vendorApi = new Vendorapis();

        vendorApi.setVendorId(savedVendor.getId());
        vendorApi.setApiUrl(request.getApiUrl());
        vendorApi.setApiType(request.getApiUsageType());

        Vendorapis savedApi = vendorApiRepository.save(vendorApi);

        Vehicleresponcemapping mapping = new Vehicleresponcemapping();

        mapping.setCountryId(country.getId());
        mapping.setVendorId(savedVendor.getId());
        mapping.setApiId(savedApi.getApiId());
        mapping.setVehiclenumber(request.getVehicleNumberField());
        mapping.setMake(request.getMakeField());
        mapping.setModel(request.getModelField());
        mapping.setYear(request.getYearField());

        jsonMappingRepo.save(mapping);

        Countryserviceentity service = new Countryserviceentity();

        service.setCountryCode(request.getCountryCode());
        service.setBaseUrl(request.getBaseUrl());
        service.setActive(true);

        stableRepo.save(service);

        return "Vendor Registered Successfully";
    }

    public String registerVendorData(
            Vendorentity vendor,
            Vendorapis vendorApi,
            Vehicleresponcemapping mapping,
            Countryserviceentity service
    ) {

        Vendorentity savedVendor = vendorRepo.save(vendor);

        Vendorapis savedApi = vendorApiRepository.save(vendorApi);

        mapping.setApiId(savedApi.getApiId());

        jsonMappingRepo.save(mapping);

        stableRepo.save(service);

        return "Vendor Registered Successfully";
    }
}