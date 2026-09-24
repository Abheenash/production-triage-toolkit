# Scheduled triage on AWS: EventBridge Scheduler invoking an ECS Fargate task.
#
# NOT APPLIED. This is validated Terraform, not running infrastructure -- see the note at the
# bottom of docs/operating.md for why, and `terraform validate` in CI is what keeps it honest.
#
# Why Fargate rather than Lambda, which is the reflex for "run a small thing on a schedule":
#
#   - The tool is a JVM process. Lambda can run a container image, but pays a cold-start penalty
#     on every invocation for a job that already completes in about a second of actual work.
#   - Lambda's 15-minute ceiling is irrelevant here, and its per-invocation pricing is not
#     cheaper at one run an hour.
#   - The database lives in a VPC. Both options need VPC networking; neither wins on that.
#
# The deciding factor is that a scheduled task that exits with a meaningful code maps directly
# onto an ECS task's exit code, which EventBridge and CloudWatch can alarm on without a wrapper.

terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.65"
    }
  }
}

provider "aws" {
  region = var.region
}

variable "region" {
  type        = string
  default     = "us-east-1"
  description = "Region hosting the database this runs against."
}

variable "image" {
  type        = string
  description = "Container image for the toolkit, e.g. <account>.dkr.ecr.<region>.amazonaws.com/triage:1.0.0"
}

variable "subnet_ids" {
  type        = list(string)
  description = "Private subnets with a route to the database. Public subnets are not required."
}

variable "security_group_ids" {
  type        = list(string)
  description = "Security group permitted to reach PostgreSQL on 5432."
}

variable "db_password_secret_arn" {
  type        = string
  description = "Secrets Manager ARN holding the read-only role's password. Never a plaintext variable."
}

variable "db_host" {
  type        = string
  description = "Database endpoint."
}

variable "db_name" {
  type    = string
  default = "bookings"
}

variable "db_user" {
  type        = string
  default     = "triage_readonly"
  description = "A role with CONNECT and SELECT and nothing else. See docs/operating.md."
}

variable "schedule" {
  type        = string
  default     = "cron(17 * * * ? *)"
  description = "Hourly at :17, off the hour so a fleet does not stampede the database."
}

locals {
  name = "production-triage-toolkit"
}

resource "aws_cloudwatch_log_group" "triage" {
  name              = "/ecs/${local.name}"
  retention_in_days = 30
}

resource "aws_ecs_cluster" "triage" {
  name = local.name
}

# The task role is empty on purpose: the tool needs no AWS API access at all. It opens one
# database connection and writes to stdout. Anything it could be granted here would be a
# permission it does not use, which is a permission that can only ever be misused.
resource "aws_iam_role" "task" {
  name = "${local.name}-task"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# The EXECUTION role is different: it is used by the ECS agent, not by the process, and needs
# exactly two things -- pull the image, and inject the secret.
resource "aws_iam_role" "execution" {
  name = "${local.name}-execution"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secret" {
  name = "${local.name}-read-db-secret"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = var.db_password_secret_arn
    }]
  })
}

resource "aws_ecs_task_definition" "triage" {
  family                   = local.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  # 1 vCPU, because the single-CPU benchmark measured a full 15-check run at 1,228 ms against
  # 10 million bookings. Paying for more would buy nothing. See docs/benchmark.md.
  cpu                = "1024"
  memory             = "2048"
  execution_role_arn = aws_iam_role.execution.arn
  task_role_arn      = aws_iam_role.task.arn

  container_definitions = jsonencode([{
    name      = "triage"
    image     = var.image
    essential = true
    command   = ["--format", "json", "--no-color"]

    environment = [
      { name = "PGHOST", value = var.db_host },
      { name = "PGPORT", value = "5432" },
      { name = "PGDATABASE", value = var.db_name },
      { name = "PGUSER", value = var.db_user },
    ]

    # Injected by the ECS agent at start. The password never appears in the task definition,
    # in an environment variable in source control, or on a command line.
    secrets = [
      { name = "PGPASSWORD", valueFrom = var.db_password_secret_arn },
    ]

    readonlyRootFilesystem = true
    user                   = "999:999"

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.triage.name
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = "triage"
      }
    }
  }])
}

resource "aws_iam_role" "scheduler" {
  name = "${local.name}-scheduler"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "scheduler.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "scheduler_run_task" {
  name = "${local.name}-run-task"
  role = aws_iam_role.scheduler.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ecs:RunTask"]
        Resource = ["${aws_ecs_task_definition.triage.arn_without_revision}:*"]
        Condition = {
          ArnLike = { "ecs:cluster" = aws_ecs_cluster.triage.arn }
        }
      },
      {
        Effect   = "Allow"
        Action   = ["iam:PassRole"]
        Resource = [aws_iam_role.task.arn, aws_iam_role.execution.arn]
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        }
      }
    ]
  })
}

resource "aws_scheduler_schedule" "triage" {
  name                         = local.name
  schedule_expression          = var.schedule
  schedule_expression_timezone = "Etc/UTC"

  flexible_time_window {
    # Spread the start across five minutes so a fleet does not connect in lockstep -- the same
    # reasoning as RandomizedDelaySec in the systemd timer.
    mode                      = "FLEXIBLE"
    maximum_window_in_minutes = 5
  }

  target {
    arn      = aws_ecs_cluster.triage.arn
    role_arn = aws_iam_role.scheduler.arn

    ecs_parameters {
      task_definition_arn = aws_ecs_task_definition.triage.arn
      launch_type         = "FARGATE"

      network_configuration {
        subnets          = var.subnet_ids
        security_groups  = var.security_group_ids
        assign_public_ip = false
      }
    }

    retry_policy {
      # Exit code 1 means findings, and a retry will find them again. Retrying a diagnostic
      # against a database that is already unhappy is the wrong instinct.
      maximum_retry_attempts = 0
    }
  }
}

# Exit code 2 means the run could not complete, which is the only outcome worth paging on from
# the schedule itself. Findings are surfaced by whatever consumes the JSON in the log group.
resource "aws_cloudwatch_log_metric_filter" "incomplete_run" {
  name           = "${local.name}-incomplete-run"
  log_group_name = aws_cloudwatch_log_group.triage.name
  pattern        = "{ $.exitCode = 2 }"

  metric_transformation {
    name      = "IncompleteTriageRuns"
    namespace = "ProductionTriageToolkit"
    value     = "1"
    unit      = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "incomplete_run" {
  alarm_name          = "${local.name}-incomplete-run"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  threshold           = 1
  period              = 3600
  statistic           = "Sum"
  namespace           = "ProductionTriageToolkit"
  metric_name         = "IncompleteTriageRuns"
  treat_missing_data  = "notBreaching"
  alarm_description   = "A scheduled triage run exited 2: at least one check could not produce an answer, so the result is not trustworthy."
}

output "cluster_arn" {
  value = aws_ecs_cluster.triage.arn
}

output "schedule_name" {
  value = aws_scheduler_schedule.triage.name
}

output "log_group" {
  value = aws_cloudwatch_log_group.triage.name
}
