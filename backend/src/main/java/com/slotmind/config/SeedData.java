package com.slotmind.config;

import com.slotmind.entity.Appointment;
import com.slotmind.entity.Provider;
import com.slotmind.entity.Slot;
import com.slotmind.enums.AppointmentStatus;
import com.slotmind.enums.AppointmentType;
import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.SlotStatus;
import com.slotmind.enums.Specialty;
import com.slotmind.repository.AppointmentRepository;
import com.slotmind.repository.ProviderRepository;
import com.slotmind.repository.SlotRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Configuration
public class SeedData {

    @Bean
    CommandLineRunner initializeDemoData(
            ProviderRepository providers,
            SlotRepository slots,
            AppointmentRepository appointments) {
        return args -> {
            if (providers.count() > 0) {
                return;
            }

            Provider sarah = new Provider(
                    "PRV-001", "Dr. Sarah Chen", Specialty.GENERAL, ProviderStatus.BUSY, 0);
            Provider james = new Provider(
                    "PRV-002", "Dr. James Okonkwo", Specialty.SPECIALIST, ProviderStatus.AVAILABLE, 0);
            Provider priya = new Provider(
                    "PRV-003", "Dr. Priya Sharma", Specialty.PEDIATRIC, ProviderStatus.BUSY, 0);
            Provider rahul = new Provider(
                    "PRV-004", "Dr. Rahul Verma", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
            Provider ananya = new Provider(
                    "PRV-005", "Dr. Ananya Iyer", Specialty.SPECIALIST, ProviderStatus.BUSY, 0);

            providers.saveAll(List.of(sarah, james, priya, rahul, ananya));

            Instant now = Instant.now();
            Instant scheduleStart = now.truncatedTo(ChronoUnit.MINUTES);
            Slot sarahMorning = new Slot(
                    "SLT-001", sarah, scheduleStart.plusSeconds(3_600), 30, SlotStatus.BOOKED, 0);
            Slot sarahAfternoon = new Slot(
                    "SLT-002", sarah, scheduleStart.plusSeconds(7_200), 30, SlotStatus.BOOKED, 0);
            Slot priyaMorning = new Slot(
                    "SLT-003", priya, scheduleStart.plusSeconds(5_400), 45, SlotStatus.BOOKED, 0);
            Slot jamesOpen = new Slot(
                    "SLT-004", james, scheduleStart.plusSeconds(10_800), 30, SlotStatus.OPEN, 0);
            Slot rahulMorning = new Slot(
                    "SLT-005", rahul, scheduleStart.plusSeconds(9_000), 30, SlotStatus.OPEN, 0);
            Slot ananyaMorning = new Slot(
                    "SLT-006", ananya, scheduleStart.plusSeconds(14_400), 60, SlotStatus.BOOKED, 0);
            Slot rahulAfternoon = new Slot(
                    "SLT-007", rahul, scheduleStart.plusSeconds(18_000), 30, SlotStatus.OPEN, 0);

            slots.saveAll(List.of(
                    sarahMorning,
                    sarahAfternoon,
                    priyaMorning,
                    jamesOpen,
                    rahulMorning,
                    ananyaMorning,
                    rahulAfternoon));

            appointments.saveAll(List.of(
                    new Appointment(
                            "APT-001", "John Martinez", AppointmentType.ROUTINE, sarah,
                            sarahMorning, AppointmentStatus.CONFIRMED, now, now, 0),
                    new Appointment(
                            "APT-002", "Emily Watson", AppointmentType.FOLLOW_UP, sarah,
                            sarahAfternoon, AppointmentStatus.CONFIRMED, now, now, 0),
                    new Appointment(
                            "APT-003", "Aisha Patel", AppointmentType.URGENT, priya,
                            priyaMorning, AppointmentStatus.CONFIRMED, now, now, 0),
                    new Appointment(
                            "APT-004", "Robert Kim", AppointmentType.ROUTINE, ananya,
                            ananyaMorning, AppointmentStatus.CONFIRMED, now, now, 0)));
        };
    }
}
