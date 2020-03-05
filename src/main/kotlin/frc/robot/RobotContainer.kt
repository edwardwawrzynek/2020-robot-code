/*----------------------------------------------------------------------------*/
/* Copyright (c) 2018-2019 FIRST. All Rights Reserved.                        */
/* Open Source Software - may be modified and shared by FRC teams. The code   */
/* must be accompanied by the FIRST BSD license file in the root directory of */
/* the project.                                                               */
/*----------------------------------------------------------------------------*/

package frc.robot

import com.ctre.phoenix.motorcontrol.can.WPI_TalonSRX
import com.kauailabs.navx.frc.AHRS
import com.revrobotics.CANSparkMax
import com.revrobotics.CANSparkMaxLowLevel.MotorType
import com.revrobotics.ColorSensorV3
import edu.wpi.first.wpilibj.*
import edu.wpi.first.wpilibj.GenericHID.Hand.kLeft
import edu.wpi.first.wpilibj.GenericHID.Hand.kRight
import edu.wpi.first.wpilibj.XboxController.Button.*
import edu.wpi.first.wpilibj.drive.DifferentialDrive
import edu.wpi.first.wpilibj.smartdashboard.SendableChooser
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard
import edu.wpi.first.wpilibj2.command.*
import edu.wpi.first.wpilibj2.command.button.JoystickButton
import edu.wpi.first.wpilibj2.command.button.Trigger
import frc.robot.commands.*
import frc.robot.subsystems.*
import frc.robot.triggers.EndgameTrigger

/**
 * This class is where the bulk of the robot should be declared.  Since Command-based is a
 * "declarative" paradigm, very little robot logic should actually be handled in the {@link Robot}
 * periodic methods (other than the scheduler calls).  Instead, the structure of the robot
 * (including subsystems, commands, and button mappings) should be declared here.
 */
class RobotContainer {
    // The robot's subsystems and commands are defined here...

    var m_autoCommandChooser: SendableChooser<Command> = SendableChooser()

    /* controller0 - secondary driver controller */
    val controller0 = XboxController(1)
    /* controller1 - primary driver controller (overriden by controller0) */
    val controller1 = XboxController(0)

    /** --- setup drivetrain --- **/
    val motorFrontLeft = WPI_TalonSRX(Constants.kFrontLeftPort)
    val motorBackLeft = WPI_TalonSRX(Constants.kBackLeftPort)
    val motorFrontRight = WPI_TalonSRX(Constants.kFrontRightPort)
    val motorBackRight = WPI_TalonSRX(Constants.kBackRightPort)

    /* keep speeds same on motors on each side */
    val motorsLeft = SpeedControllerGroup(motorFrontLeft, motorBackLeft)
    val motorsRight = SpeedControllerGroup(motorFrontRight, motorBackRight)

    val gyro = AHRS()

    val drivetrain = DrivetrainSubsystem(DifferentialDrive(motorsLeft, motorsRight), gyro)
    val shooter = ShooterSubsystem(CANSparkMax(Constants.kShooterPort, MotorType.kBrushless))
    val intake = IntakeSubsystem(WPI_TalonSRX(Constants.kIntakePort), WPI_TalonSRX(Constants.kIntake2Port))
    val indexer = IndexerSubsystem(WPI_TalonSRX(Constants.kIndexerPort))
    val gate = GateSubsystem(WPI_TalonSRX(Constants.kGatePort), ColorSensorV3(I2C.Port.kOnboard))
    val winch0 = WinchSubsystem(WPI_TalonSRX(Constants.kWinch0Port))
    val winch1 = WinchSubsystem(WPI_TalonSRX(Constants.kWinch1Port))
    val lights = LEDSubsystem(AddressableLED(Constants.kLED0Port), 60, DriverStation.getInstance())

    /*** --- commands --- ***/
    //drive by a joystick (controller1)
    val XboxDriveCommand = XboxDrive(drivetrain, controller1)

    /** -- 0 point autos -- **/
    val noAuto = DriveDoubleSupplier(drivetrain, { 0.0 }, { 0.0 })

    /** --- 5 point autos --- **/
    //backup simple auto
    val backupAuto = DriveDoubleSupplier(drivetrain, { -0.3 }, { 0.0 }).withTimeout(2.0)
    //forward simple auto
    val forwardAuto = DriveDoubleSupplier(drivetrain, { 0.3 }, { 0.0 }).withTimeout(2.0)

    /** -- more than 5 point autos (hopefully) -- **/
    /* Power Port Vision
     * rock the robot forward then back, dropping the intake (0.4 s)
     * drive forward while waiting for intake to fall fully (0.75 s)
     * run vision alignment till target is going out of frame (variable)
     * in parallel:
     *      drive forward to finalize alignment (0.5 s)
     *      run shoot sequence (kAutoShootTime ~4.0 s)
     * drive robot backwards while turning to 90 degrees off current angle
     * drive robot forwards while turning to 90 degrees off current angle
     */
    var visionAutoInitialAngle = 0.0
    val visionAuto = {
        SequentialCommandGroup(
            DriveDoubleSupplier(drivetrain, { 0.75 }, { 0.0 }).withTimeout(0.2),
            DriveDoubleSupplier(drivetrain, { -0.75 }, { 0.0 }).withTimeout(0.2),
            DriveDoubleSupplier(drivetrain, { 0.3 }, { 0.0 }).withTimeout(0.75),
            VisionHighGoal(drivetrain, 0.3),
            ParallelCommandGroup(
                DriveDoubleSupplier(drivetrain, { 0.3 }, { 0.0 }).withTimeout(0.5),
                CompositeShoot(intake, indexer, gate, shooter, Constants.kAutoShootTime)
            ),
            InstantCommand({ visionAutoInitialAngle = drivetrain.getAngle() }, arrayOf<Subsystem>()),
            DriveDoubleSupplier(drivetrain, { -0.75 }, { 0.0 }).withTimeout(Constants.kAutoBackupTime),
            //TurnToAngle(drivetrain, { visionAutoInitialAngle + 90.0 }, 0.0),
            TurnToAngle(drivetrain, { visionAutoInitialAngle + 180.0 }, 0.0)
        )
    }

    val visionHighGoalLineUpAndShoot = {
        SequentialCommandGroup(
            VisionHighGoal(drivetrain, 0.3),
            ParallelCommandGroup(
                DriveDoubleSupplier(drivetrain, { 0.3 }, { 0.0 }).withTimeout(0.5),
                CompositeShoot(intake, indexer, gate, shooter, Constants.kAutoShootTime)
            )
        )
    }

    val visionHighGoalLineUp = {
        SequentialCommandGroup(
            VisionHighGoal(drivetrain, 0.3),
            DriveDoubleSupplier(drivetrain, { 0.3 }, { 0.0 }).withTimeout(0.5)
        )
    }

    val forwardShootAutoNoIntake = SequentialCommandGroup(
        ParallelCommandGroup(
            SequentialCommandGroup(
                DriveDoubleSupplier(drivetrain, { 0.45 }, { 0.0 }).withTimeout(0.5),
                DriveDoubleSupplier(drivetrain, { -0.45 }, { 0.0 }).withTimeout(0.5),
                DriveDoubleSupplier(drivetrain, { 0.0 }, { 0.0 }).withTimeout(1.5),
                DriveDoubleSupplier(drivetrain, { 0.3 }, { 0.0 }).withTimeout(3.5)
            ),
            FixedIntakeSpeed(intake, { 0.0 }),
            FixedIndexerSpeed(indexer, { 0.0 })
        ).withTimeout(6.0),
        CompositeShoot(intake, indexer, gate, shooter, 5.0),
        ParallelCommandGroup(
            FixedIntakeSpeed(intake, { 0.0 }),
            FixedIndexerSpeed(indexer, { 0.0 })
        ),
        DriveDoubleSupplier(drivetrain, { -0.3 }, { 0.0 }).withTimeout(5.0)
    )


    /*LED Lights */
    val setAlliance = SetAlliance(lights)
    val setRed = SetColor(lights, 255, 0, 0)
    val setBlue = SetColor(lights, 0, 0, 255)
    val setWhite = SetColor(lights, 255, 255, 255)
    val setRainbow = SetRainbow(lights)

    /**
     * The container for the robot.  Contains subsystems, OI devices, and commands.
     */
    init {
        // Configure the button bindings
        configureButtonBindings()
    }

    /**
     * Use this method to define your button->command mappings.  Buttons can be created by
     * instantiating a {@link GenericHID} or one of its subclasses ({@link
     * edu.wpi.first.wpilibj.Joystick} or {@link XboxController}), and then calling passing it to a
     * {@link edu.wpi.first.wpilibj2.command.button.JoystickButton}.
     */

    fun configureButtonBindings() {
        Constants.loadConstants()
        /* controller0 overrides */

        JoystickButton(controller1, kBumperRight.value).whenHeld(
            CompositeShoot(intake, indexer, gate, shooter, 0.0)
        )

        EndgameTrigger().and(JoystickButton(controller1, kB.value)).whileActiveOnce(
            ParallelCommandGroup(
                FixedWinchSpeed(winch0, { Constants.kWinchDeploySpeed }),
                FixedWinchSpeed(winch1, { Constants.kWinchDeploySpeed })
            ).withTimeout(5.0)
        )

        /* TODO: cut intake and indexer on climb */

        EndgameTrigger().and(Trigger({ controller1.getTriggerAxis(kLeft) >= Constants.kWinchTriggerThresh })).whileActiveOnce(
            FixedWinchSpeed(winch0, { Constants.kWinchDir * controller1.getTriggerAxis(kLeft) })
        )

        EndgameTrigger().and(Trigger({ controller1.getTriggerAxis(kRight) >= Constants.kWinchTriggerThresh })).whileActiveOnce(
            FixedWinchSpeed(winch1, { Constants.kWinchDir * controller1.getTriggerAxis(kRight) })
        )

        JoystickButton(controller1, kBumperLeft.value).whenHeld(visionHighGoalLineUp())

        /* TODO: a button to cancel all active commands and return each subsystem to default command (if things go wrong) */


        /* setup default commands */
        drivetrain.defaultCommand = XboxDriveCommand
        /* default gate - run forward on X, backwards on B
         * If left bumper held, run until a ball is seen by the sensor
         */
        gate.defaultCommand = SensoredFixedGateSpeed(gate, {
            if (controller0.xButton) Constants.kGateSpeed else (
                if (controller0.bButton) -Constants.kGateSpeed else (
                    if (controller0.getBumper(kLeft)) Constants.kGateLoadSpeed else 0.0
                ))
        }, {
            /* only run in sensored mode if controller0 left bumper held */
            controller0.getBumper(kLeft)
        })

        /* default shooter - run forward on y, slowly backwards on a */
        shooter.defaultCommand = FixedShooterSpeed(shooter, {
            if (controller0.yButton) Constants.kShooterSpeed else (
                if (controller0.aButton) Constants.kShooterReverseSpeed else 0.0)
        })

        /* default indexer - run forward on left bumper, backwards on left trigger */
        indexer.defaultCommand = FixedIndexerSpeed(indexer, {
            if (controller0.getBumper(kLeft) || controller1.xButton) Constants.kIndexerSpeed else (
                if (controller0.getTriggerAxis(kLeft) >= Constants.kWinchTriggerThresh)
                    controller0.getTriggerAxis(kLeft) * -Constants.kIndexerDir else 0.0)
        })

        /* default intake - run forward on right bumper, backwards on right trigger */
        intake.defaultCommand = FixedIntakeSpeed(intake, {
            if (controller0.getBumper(kRight) || controller1.xButton) Constants.kIntakeSpeed else (
                if (controller0.getTriggerAxis(kRight) >= Constants.kWinchTriggerThresh)
                    controller0.getTriggerAxis(kRight) * -Constants.kIntakeDir else 0.0)
        })
        lights.defaultCommand = setAlliance

        /* set options for autonomous */
        m_autoCommandChooser.setDefaultOption("Power Port Vision Autonomous", visionAuto())
        m_autoCommandChooser.addOption("Backup 2s Autonomous", backupAuto)
        m_autoCommandChooser.addOption("Forward 2s Autonomous", forwardAuto)
        m_autoCommandChooser.addOption("Forward 4.5s and Shoot", forwardShootAutoNoIntake)
        m_autoCommandChooser.addOption("No auto (DON'T PICK)", noAuto)

        SmartDashboard.putData("Auto mode", m_autoCommandChooser)

    }

    fun getAutonomousCommand(): Command {
        // Return the selected command
        return m_autoCommandChooser.selected
    }
}
