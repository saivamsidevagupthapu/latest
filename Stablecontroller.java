package com.stablespringbootproject.Controller;

import org.springframework.web.bind.annotation.*;

import com.stablespringbootproject.Dto.VendorRegisterRequest;
import com.stablespringbootproject.Dto.Stablerequest;
import com.stablespringbootproject.Dto.Stableresponse;
import com.stablespringbootproject.Service.Stableservice;

@RestController
@RequestMapping("/stable")
public class Stablecontroller {

    private final Stableservice service;

    public Stablecontroller(Stableservice service) {
        this.service = service;
    }

    @PostMapping("/vehicle")
    public Stableresponse getVehicle(
            @RequestHeader(value = "vehicleno", required = false) String vehicleno,
            @RequestHeader(value = "vendorname", required = false) String vendorname,
            @RequestHeader(value = "country", required = false) String country,
            @RequestHeader(value = "phone_number", required = false) String phoneNumber,
            @RequestHeader(value = "api_usage_type", required = false) String apiUsageType,
            @RequestBody(required = false) Stablerequest request
    ) {
        // Allow calling either with JSON body OR with headers (Postman "Headers" tab).
        if (request == null) {
            request = new Stablerequest();
        }

        if (request.getVehicleno() == null || request.getVehicleno().isEmpty()) {
            request.setVehicleno(vehicleno);
        }
        if (request.getVendorname() == null || request.getVendorname().isEmpty()) {
            request.setVendorname(vendorname);
        }
        if (request.getCountry() == null || request.getCountry().isEmpty()) {
            request.setCountry(country);
        }
        if (request.getPhone_number() == null || request.getPhone_number().isEmpty()) {
            request.setPhone_number(phoneNumber);
        }
        if (request.getApi_usage_type() == null || request.getApi_usage_type().isEmpty()) {
            request.setApi_usage_type(apiUsageType);
        }

        return service.fetchVehicle(request);
    }

    @PostMapping("/vendor/register")
    public String registerVendor(@RequestBody VendorRegisterRequest request) {
        return service.registerVendor(request);
    }
}