# Infrastructure

This directory is a placeholder; cloud infrastructure is not implemented. Terraform and staging deployment are tracked in [#34](https://github.com/studioforyou/zpi-studio/issues/34) and [#35](https://github.com/studioforyou/zpi-studio/issues/35). Their issue assignments and milestones remain the planning source.

Read the [architecture decisions](../docs/ARCHITECTURE_DECISIONS.md) for the proposed cloud architecture and provisioning approach and the confirmed storage provider. These decisions do not establish deployed resources or an operational pipeline.

Local PostgreSQL and backend configuration live in the root [docker-compose.yml](../docker-compose.yml). See [local development](../docs/LOCAL_DEVELOPMENT.md) for its verification status, and the [documentation map](../docs/README.md) for architecture and workflow sources.
